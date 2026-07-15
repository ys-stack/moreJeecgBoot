# AI RAG 缓存工程化设计与面试说明

## 1. 改造背景

项目中有两类成本较高、但重复率也较高的调用：

1. 文档分片向量化。相同文本重复上传、重新解析或重新同步时，如果每次都调用 Embedding API，会增加费用和处理时间。
2. 高频 FAQ 问答。相同用户问题反复执行 Embedding、ES 检索、Rerank 和大模型生成，会消耗大量外部资源。

仅使用本地 Caffeine 有两个明显问题：

- 应用重启后缓存全部丢失。
- 多实例部署时，各节点缓存相互独立，节点 A 更新知识库后，节点 B 仍可能返回旧答案。

因此，本次改造不是简单增加一个 `Map`，而是分别针对 Embedding 和 RAG 答案设计缓存层级、缓存作用域、数据安全和一致性策略。

## 2. 最终架构

### 2.1 Embedding 向量缓存

读取链路：

```text
Caffeine 本地缓存
        ↓ 未命中
Redis 二进制缓存
        ↓ 未命中
MySQL ai_embedding_cache
        ↓ 未命中
硅基流动 Embedding API
```

各层职责：

- Caffeine：进程内热点数据，访问最快，减少 Redis 网络调用。
- Redis：多节点共享，应用重启后仍可复用近期向量。
- MySQL：长期持久化，Redis 数据淘汰后仍能恢复，避免再次调用收费 API。
- Embedding API：只有三级缓存都未命中时才调用。

### 2.2 RAG FAQ 答案缓存

读取链路：

```text
权限范围和知识库版本计算
        ↓
Caffeine 本地答案缓存
        ↓ 未命中
Redis 加密答案缓存
        ↓ 未命中
Embedding → ES → Rerank → LLM
```

答案缓存只用于第一轮、无历史上下文的独立问题。多轮对话依赖历史消息，同一句话在不同上下文中可能含义不同，因此不能直接复用最终答案。

## 3. 为什么 Embedding 缓存不使用 sessionId

`sessionId` 表示一次聊天会话，而文档解析和向量化通常发生在后台任务或 MQ 消费线程中，本来就没有聊天会话。把 `sessionId` 放进 Embedding 缓存键会造成两个问题：

- 文档解析场景无法构造合法缓存键。
- 相同文本在不同会话下无法复用向量，违背内容寻址缓存的目的。

本实现将两个上下文明确分开：

- `EmbeddingCacheContext`：只包含租户信息，适用于文档解析、后台同步和用户查询向量化。
- `RagAnswerCacheContext`：包含租户、知识库集合、知识库版本指纹、模型和 Prompt 版本，适用于最终答案缓存。

这不是简单地把一个方法拆成两个，而是把缓存的业务作用域建模成不同类型，编译期就能减少误用。

## 4. Embedding 缓存键设计

Embedding 缓存键包含：

```text
tenantId
modelName
modelVersion
normalizationVersion
dimensions
canonicalText
```

最终使用 HMAC-SHA256 生成固定长度摘要。

设计原因：

- `tenantId`：避免不同租户的私有文本互相复用。
- `modelName`：不同模型的向量空间不兼容。
- `modelVersion`：供应商模型升级后，即使名称不变，也不能复用旧向量。
- `normalizationVersion`：文本预处理规则变化后自动使用新缓存。
- `dimensions`：避免维度配置变化时读取不兼容向量。
- `canonicalText`：相同规范化文本得到相同内容地址。
- HMAC：Redis/MySQL 中看不到原始文本，也不能通过普通 SHA-256 字典攻击直接猜测文本。

生产环境必须配置不少于 32 个字符的独立密钥：

```yaml
practice:
  cache:
    hmac-secret: ${PRACTICE_CACHE_HMAC_SECRET}
```

密钥不能提交到 Git，建议通过环境变量、Kubernetes Secret 或密钥管理服务注入。

## 5. 向量存储与校验

向量不再使用 JSON 字符串保存，而是将 `float[]` 按大端序编码为 `byte[]`：

- 存储空间更小。
- Redis 和 MySQL 反序列化速度更稳定。
- 不受浮点数字符串格式影响。

读取向量时会校验：

- 实际维度是否等于配置维度。
- 是否包含 `NaN` 或无穷大。
- 是否为全零向量。
- MySQL 二进制数据的 SHA-256 checksum 是否一致。

如果 MySQL 缓存记录损坏，会删除损坏记录并回退到 Embedding API，避免唯一键阻止正确向量重新写入。

缓存属于性能优化项。Redis 或 `ai_embedding_cache` 表临时异常时，主链路会降级调用 Embedding API，不会因为缓存系统故障导致文档完全无法向量化。

## 6. 缓存击穿处理

多个节点同时请求同一个未缓存文本时，可能同时调用收费 API。本实现使用 Redis `SET NX EX` 分布式锁：

1. 获得锁的请求负责调用 Embedding API 并回填缓存。
2. 未获得锁的请求短时间轮询缓存。
3. 等待超时后允许降级调用 API，避免长期阻塞。
4. 解锁使用 Lua 脚本比较 token 后删除，防止误删其他请求的新锁。

锁是减少重复调用的优化，不作为业务正确性的唯一保障。即使 Redis 不可用，也会允许请求继续执行。

## 7. RAG 答案缓存键设计

答案缓存键包含：

```text
tenantId
排序后的 knowledgeBaseIds
knowledgeVersionFingerprint
modelName
promptVersion
normalizedQuestion
```

关键点：

- 不使用 `sessionId`，因为只缓存第一轮独立 FAQ；这样不同会话可以安全复用同一答案。
- 多轮问题不缓存最终答案，避免忽略历史上下文。
- 知识库 ID 集合表示本次请求实际有权访问的知识范围，权限范围不同不会共享答案。
- 知识库版本指纹保证知识内容变化后旧 key 自动失效。
- 模型或 Prompt 变化后生成新 key，旧答案不会污染新逻辑。
- Redis 中不保存原始问题，只保存 HMAC 摘要。

首轮缓存判断必须早于“问题改写”模型调用。否则虽然答案缓存命中，系统仍然先调用一次大模型进行 query rewrite，无法真正保护模型。本次已经调整为：

```text
权限与版本计算 → 答案缓存查询 → 未命中后才做检索或模型调用
```

同时，当前刚保存的用户消息会从历史窗口中排除，避免相同问题同时作为“历史消息”和“当前问题”重复发送给模型。

## 8. 数据安全设计

### 8.1 租户隔离

Embedding 和 RAG 答案缓存键都包含 `tenantId`。即使两个租户上传了相同文本，也不会共享私有缓存记录。

### 8.2 缓存键防猜测

原始文档分片和原始问题不直接作为 Redis key，统一使用 HMAC-SHA256。普通 SHA-256 对短 FAQ 容易被字典枚举，HMAC 需要服务端密钥才能计算。

### 8.3 Redis 答案加密

RAG 答案、引用和上下文可能包含业务敏感信息，因此 Redis value 使用 AES-256-GCM 加密：

- 每次写入使用随机 12 字节 IV。
- GCM 同时提供机密性和完整性校验。
- 密文被篡改后解密会失败，并主动删除异常缓存。
- AES 密钥由 `practice.cache.hmac-secret` 通过独立上下文派生，不直接复用原始 HMAC 密钥。

生产环境仍应同时启用 Redis ACL、TLS、内网访问控制和备份权限控制。应用层加密不能替代基础设施安全。

### 8.4 日志脱敏

RAG、向量检索和 Rerank 日志不再打印原始用户问题，只记录问题长度、命中数量和固定长度缓存摘要，避免敏感问题进入日志平台。

### 8.5 审计链路

答案缓存命中后仍然保存用户消息、助手消息，并更新会话消息计数。缓存只跳过昂贵的检索和模型调用，不跳过业务审计。

因此，更准确的性能表述是：

- Caffeine 查找本身通常是亚毫秒级。
- 完整接口仍包含权限查询和聊天消息落库，不能承诺端到端严格 `0ms`。
- 可以表述为“缓存命中时不再调用 ES、Rerank 和大模型，响应时间由秒级下降到毫秒级”。

## 9. 知识库版本与缓存一致性

`ai_knowledge_base.cache_version` 是知识库内容版本号。以下操作成功后会原子递增版本：

- ES 文档向量写入成功。
- ES 文档向量删除成功。
- 直接向量化接口成功。
- 知识库名称、权限、模型等配置被编辑。
- 知识库被删除。

答案缓存键包含所有相关知识库的 `id:version` 指纹。因此版本一旦递增，新请求自然生成新 key，不会命中旧答案。

版本化 key 是正确性的主要保障，双删是缩短多节点旧版本缓存窗口的补充机制。

## 10. 为什么使用 ActiveMQ，而不是 Redis 发布订阅

项目已经引入 ActiveMQ，因此复用现有中间件，避免为了缓存失效再维护一套 Redis Pub/Sub 消费模型。

使用 ActiveMQ Virtual Topic：

```text
VirtualTopic.airag.practice.cache.invalidate
```

生产者向 Topic 发送一次消息，每个应用实例通过自己的 Queue 消费同一事件：

```text
Consumer.airag-node-01.VirtualTopic.airag.practice.cache.invalidate
Consumer.airag-node-02.VirtualTopic.airag.practice.cache.invalidate
```

每个实例必须配置不同的 Queue。如果多个节点共用同一个 Queue，ActiveMQ 会按竞争消费者处理，只有一个节点收到消息，其他节点的 Caffeine 将无法及时清理。

示例配置：

```yaml
practice:
  cache:
    mq:
      consumer-queue: Consumer.${INSTANCE_ID}.VirtualTopic.airag.practice.cache.invalidate
```

`INSTANCE_ID` 必须在实例生命周期内稳定且唯一。

项目现有 ES 同步消息仍然使用 Queue `JmsTemplate`。缓存失效单独使用 Topic `JmsTemplate`，避免把原有点对点消息误发到 Topic。

## 11. 延迟双删原理

知识库发生变化时执行：

1. 更新数据库前删除本地版本缓存和 Redis 版本缓存。
2. 立即发送一次 ActiveMQ 失效消息，清理其他节点的 Caffeine。
3. 原子递增数据库 `cache_version`。
4. 事务提交后立即再发送一次失效消息。
5. 事务提交后发送一条延迟 1 秒的失效消息，完成第二次删除。

为什么需要延迟删除：

```text
线程 A 删除缓存
线程 B 在数据库提交前读到旧版本，并把旧版本重新写入缓存
线程 A 提交新版本
延迟消息再次删除线程 B 回填的旧缓存
```

ActiveMQ 延迟消息使用 `AMQ_SCHEDULED_DELAY`，Broker 必须开启：

```xml
<broker schedulerSupport="true" ...>
```

MQ 发送失败不会回滚知识库主业务。原因是缓存失效属于派生操作，数据库版本号和本地短 TTL 仍能提供最终一致性兜底。

## 12. SQL 字段说明

SQL 文件：`docs/sql/20260715_embedding_and_rag_cache.sql`

### 12.1 ai_knowledge_base.cache_version

知识库缓存版本。初始值为 1，知识内容、权限或模型配置变化后递增。该字段不允许前端通过普通 `updateById` 覆盖，代码使用独立原子 SQL 更新。

### 12.2 ai_embedding_cache

| 字段 | 含义 |
| --- | --- |
| `id` | MyBatis-Plus 主键 |
| `cache_key` | HMAC-SHA256 唯一缓存键 |
| `tenant_id` | 租户隔离字段 |
| `model_name` | Embedding 模型名称 |
| `model_version` | 模型业务版本，模型升级时修改 |
| `normalization_version` | 文本归一化算法版本 |
| `dimensions` | 向量维度 |
| `vector_data` | 大端序编码的 float 二进制数据 |
| `vector_checksum` | `vector_data` 的 SHA-256 校验和 |
| `create_time` | 首次写入时间 |
| `update_time` | 缓存记录更新时间 |
| `last_hit_time` | 最近一次 MySQL 回源命中时间，用于清理冷数据 |

## 13. 缓存容量、TTL 与淘汰策略

Embedding：

- Caffeine 最大权重 64 MB，按向量字节数计权，TTL 24 小时。
- Redis TTL 7 天并增加随机抖动，避免大量 key 同时过期。
- MySQL 长期保存，可根据 `last_hit_time` 定期分批清理。

RAG 答案：

- Caffeine 最大权重 32 MB，按答案和上下文估算大小，TTL 2 分钟。
- Redis TTL 10 分钟并增加随机抖动。
- 知识库版本进入 key，内容变化后即使旧 key 尚未物理删除，也不会被新请求命中。

## 14. 可观测性

新增低基数 Micrometer 指标：

```text
practice.cache.hit.total{cache,level}
practice.cache.miss.total{cache}
practice.embedding.api.request.total
practice.embedding.api.text.total
```

标签只使用固定值，例如 `embedding`、`rag-answer`、`caffeine`、`redis`、`mysql`。不能把租户 ID、问题、知识库 ID 或缓存 key 作为标签，否则会产生高基数时序数据。

建议重点观察：

- 各级缓存命中率。
- Embedding API 调用次数和文本条数。
- Redis/MySQL 缓存异常日志。
- ActiveMQ 缓存失效消息堆积和死信队列。
- 知识库更新后旧答案的实际失效延迟。

## 15. 测试与验证

当前新增测试覆盖：

- float 向量编码、解码和非法向量校验。
- HMAC 长度分隔、防碰撞和文本归一化。
- Embedding/RAG 缓存上下文标准化。
- AES-GCM 加解密和密文篡改检测。

验证命令：

```powershell
$env:JAVA_HOME='D:\soft\jdk17'
$env:Path='D:\soft\jdk17\bin;' + $env:Path

D:\soft\apache-maven-3.9.16\bin\mvn.cmd `
  -pl jeecg-boot-module/jeecg-boot-module-airag `
  -am `
  '-Dtest=FloatVectorCodecTest,PracticeCacheKeyHasherTest,CacheContextTest,PracticeCachePayloadCipherTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DskipTests=false' `
  '-Dmaven.test.skip=false' test
```

## 16. 面试回答参考

### 16.1 一分钟版本

> 我们把缓存分成了两类。文档 Embedding 使用 Caffeine、Redis、MySQL 三级缓存，缓存键由租户、模型版本、向量维度、归一化版本和分片文本做 HMAC-SHA256 得到。Redis 保存二进制向量，MySQL 做长期持久化，三级都未命中才调用硅基流动 API；并用 Redis 分布式锁减少并发击穿。
>
> 对高频 FAQ，我们只缓存第一轮、没有历史上下文的最终 RAG 答案，key 中包含租户、用户实际可访问的知识库集合、知识库版本指纹、模型和 Prompt 版本。知识库变化时原子递增版本，并通过 ActiveMQ Virtual Topic 通知所有节点清理 Caffeine，同时做提交后延迟双删。安全上不保存原始问题，key 使用 HMAC，Redis 答案使用 AES-GCM 加密，并避免在日志中打印问题原文。

### 16.2 三分钟版本

> 最初只有本地 Caffeine，单节点有效，但重启会丢失，多节点也会出现数据不一致，所以我先区分了 Embedding 缓存和答案缓存，因为两者的业务作用域不同。
>
> Embedding 是文本和模型的确定性结果，不应该绑定聊天 sessionId。文档解析、MQ 同步本来就没有 sessionId，因此我设计了只包含 tenantId 的 EmbeddingCacheContext，并把模型名、模型版本、归一化版本、维度和规范化文本一起放进 HMAC key。读取顺序是 Caffeine、Redis、MySQL、API。向量用二进制保存并做维度、NaN、全零和 checksum 校验。并发 miss 时使用 Redis 锁，锁失败或缓存故障时允许主链路降级，保证缓存不会成为单点故障。
>
> 最终答案与知识库内容和权限范围相关，所以答案 key 包含租户、排序后的知识库 ID、每个知识库的 cacheVersion、模型、Prompt 版本和问题摘要。只缓存第一轮问题，多轮对话不缓存，因为同一句话可能依赖不同历史。缓存命中判断放在 query rewrite 之前，确保命中时不会多调用一次大模型。
>
> 一致性方面，cacheVersion 是主要保障。知识库内容变化后版本递增，新请求自然使用新 key。为了让各节点尽快删除本地 Caffeine，我使用项目已有的 ActiveMQ Virtual Topic，每个实例绑定独立 Queue。更新前删一次，事务提交后立即删一次，再发 1 秒延迟消息删一次，解决并发读把旧版本重新回填的问题。
>
> 安全方面，所有 key 都做租户隔离和 HMAC，Redis 中的答案使用 AES-GCM 加密，日志不记录问题原文。可观测性上记录分层命中率、miss 数和真实 API 调用次数，便于验证成本优化是否真实。

### 16.3 常见追问

#### 为什么不把 sessionId 统一放到所有缓存 key？

因为 sessionId 是聊天上下文，不是文档向量的业务维度。Embedding 绑定 sessionId 会降低复用率，并使后台文档任务无法使用缓存。最终答案也只缓存首轮独立问题，因此使用权限范围和知识版本比 sessionId 更准确。

#### 为什么 MySQL 也做缓存？

Redis 适合热点共享，但可能因过期、淘汰或运维操作丢失。Embedding API 有直接成本，MySQL 持久层可以在 Redis 丢失后恢复向量，避免重复付费。

#### 为什么答案缓存不做语义相似匹配？

语义近似不代表答案可以安全复用，尤其是金额、日期、版本号等细微差异。当前采用保守的归一化精确匹配，先保证正确性。如果以后做语义缓存，需要增加相似度阈值、实体一致性校验、风险分级和人工评测集。

#### 双删能保证强一致吗？

不能。双删是降低并发回填旧缓存概率的最终一致性方案。这里真正避免旧答案命中的核心是版本化 key；ActiveMQ 双删用于快速清理各节点本地缓存和 Redis 中的版本缓存。

#### 为什么不用 Redis 发布订阅？

项目已经有 ActiveMQ。复用现有消息基础设施可以减少组件和运维复杂度；Virtual Topic 能让每个实例通过独立 Queue 都收到同一失效事件，并且支持持久化和延迟消息。

#### 能不能说缓存命中是 0ms？

不建议。Caffeine 查找可以接近亚毫秒，但完整请求还有权限校验、会话消息保存和网络框架开销。更严谨的说法是“缓存命中后跳过 ES、Rerank 和 LLM，端到端响应由秒级下降到毫秒级”。

## 17. 上线检查清单

- 执行 `docs/sql/20260715_embedding_and_rag_cache.sql`。
- 所有实例配置相同的 `practice.cache.hmac-secret`，且长度不少于 32 字符。
- 密钥通过安全配置中心或环境变量注入，不写入仓库。
- 每个实例配置唯一且稳定的 `practice.cache.mq.consumer-queue`。
- ActiveMQ Broker 开启 `schedulerSupport=true`。
- Redis 开启认证、ACL、TLS 或可信内网隔离。
- 确认 ActiveMQ 缓存失效 Queue 没有堆积。
- 观察缓存命中率和 Embedding API 调用量是否符合预期。
- 修改 Embedding 模型时同步更新 `practice.embed.model-version`。
- 修改文本归一化逻辑时同步更新 `practice.embed.normalization-version`。
- 修改 RAG Prompt 时同步更新 `practice.rag.prompt-version`。

