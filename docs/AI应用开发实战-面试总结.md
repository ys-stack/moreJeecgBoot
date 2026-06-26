# AI 应用开发实战 — 面试技术总结

> 基于 JeecgBoot 平台，从零构建企业级 AI 应用的完整实践。
> 涵盖：大模型对话、RAG 检索增强生成、Prompt 工程、Tool Calling、Embedding 向量化、ES 向量存储。

---

## 一、项目概述

### 1.1 项目定位

在 JeecgBoot（企业级低代码平台）上，独立实现一套 **AI 应用开发脚手架**，覆盖从模型对话到知识库检索到工具调用的完整链路。

### 1.2 技术栈

| 层次 | 技术选型 |
|------|---------|
| 后端框架 | Spring Boot 3 + MyBatis-Plus + JeecgBoot |
| AI 框架 | LangChain4j 1.12（模型抽象层） |
| LLM 模型 | mimo-v2.5-pro（主模型）、通义千问、智谱GLM、文心一言（多模型适配） |
| Embedding | 硅基流动 bge-m3（1024维，OpenAI 兼容接口） |
| 向量存储 | Elasticsearch 8.x（dense_vector + HNSW + kNN） |
| 文档解析 | Python FastAPI + Apache Tika + Apache POI |
| 前端 | Vue 3 + Ant Design Vue |
| 异步/并发 | CompletableFuture + 自定义线程池 + @Async |

---

## 二、核心模块与技术原理

### 2.1 大模型对话服务（Week 1）

#### 架构设计

```
前端 (SSE) → Controller → ChatService → LangChain4j OpenAiChatModel → LLM API
                ↓
          StreamingChatModelListener（流式监听）
                ↓
          SseEmitter（Server-Sent Events 推送到前端）
```

#### 关键技术点

**1. 流式响应（Streaming）**

传统 HTTP 是"请求-等待-响应"模式，AI 对话需要流式输出（逐字显示）。

- **后端**：`StreamingChatLanguageModel` + `ChatModelListener`，模型每生成一个 token 就触发 `onPartialResponse`，通过 `SseEmitter` 推送
- **前端**：`EventSource` 或 `fetch + ReadableStream` 接收 SSE 流
- **协议**：Server-Sent Events（SSE），基于 HTTP 长连接，服务端单向推送

```java
// 核心调用链
streamingModel.chat(messages, new StreamingChatModelListener() {
    @Override
    public void onPartialResponse(String partialResponse) {
        emitter.send(partialResponse);  // 逐 token 推送
    }
    @Override
    public void onCompleteResponse(ChatResponse response) {
        emitter.complete();  // 流结束
    }
});
```

**2. 多模型适配**

不同厂商的 API 格式略有差异，LangChain4j 提供统一抽象：

```
OpenAiChatModel      → OpenAI / 硅基流动 / 深度求索（兼容接口）
ZhipuAiChatModel     → 智谱 GLM
QianfanChatModel     → 百度文心一言
DashScopeChatModel   → 阿里通义千问
```

通过 `@ConfigurationProperties` 绑定不同模型配置，运行时动态切换。

**3. 对话上下文管理**

LLM 是无状态的，每次请求必须带上历史消息。实现方案：
- MySQL 存储会话（`ai_chat_session`）和消息（`ai_chat_message`）
- 查询最近 N 条消息拼装成 `List<ChatMessage>` 传给模型
- Token 窗口管理：超出模型上下文限制时截断早期消息

#### 面试常见问题

> **Q: SSE 和 WebSocket 的区别？**
> SSE 是服务端单向推送（基于 HTTP），WebSocket 是双向通信。AI 对话场景只需要服务端推送，SSE 更简单、兼容性更好、自动重连。

> **Q: 流式响应如何处理错误？**
> 流已经开始推送后，如果模型报错，需要通过 SSE 的 error 事件通知前端，同时调用 `emitter.completeWithError()` 清理连接。

---

### 2.2 RAG 检索增强生成（Week 2）

#### 核心问题

LLM 有两个致命缺陷：
1. **知识截止**：训练数据有时间边界，不知道最新信息
2. **幻觉**：对不确定的问题会"编造"答案

**RAG 的解决方案**：先检索相关文档，再让 LLM 基于检索结果回答。

#### 完整链路

```
用户提问
   ↓
┌─────────────────────────────────────────────────┐
│ 1. 文档处理（离线）                                │
│    PDF/Word/Markdown → 文本提取 → 分片 → Embedding │
│    → 写入 Elasticsearch                           │
└─────────────────────────────────────────────────┘
   ↓
┌─────────────────────────────────────────────────┐
│ 2. 检索增强（在线）                                │
│    用户 Query → Embedding → ES kNN 检索           │
│    → 取 topK 相关分片 → 拼入 Prompt                │
│    → 发送给 LLM → 基于上下文生成回答                │
└─────────────────────────────────────────────────┘
```

#### 关键技术点

**1. 文档解析**

异构文档（PDF、Word、Markdown、HTML）统一解析为纯文本：

- **Python 解析服务**：FastAPI + Apache Tika，支持 40+ 文件格式
- **解析流程**：上传文件 → HTTP POST 到 Python 服务 → 返回结构化文本
- **并发控制**：自定义线程池（核心5/最大10/队列200），防止同时解析太多文件导致 OOM
- **超时保护**：单文件解析设 120s 超时，避免卡死

**2. 文本分片（Chunking）**

长文档不能整篇塞给 LLM（超 Token 限制），需要切分成小块：

- **递归字符分割**：按 `\n\n` → `\n` → `。` → `，` 逐级切分
- **重叠窗口**：相邻分片有 200 字符重叠，避免语义在边界断裂
- **元数据保留**：每个分片记录来源文件名、标题路径、分片序号

```
分片大小：1000 字符
重叠：200 字符
切分优先级：段落 > 换行 > 句号 > 逗号
```

**3. Embedding 向量化**

把文本变成浮点数数组，使"语义相似"可以数学计算：

- **模型**：bge-m3（BAAI 智源），1024 维，中英文双语
- **接口**：OpenAI 兼容格式 `POST /v1/embeddings`
- **批量处理**：每批 20 条，避免 API 超时
- **向量归一化**：bge-m3 输出已归一化，余弦相似度 = 点积

```java
// 单条
float[] vector = embeddingService.embed("Redis持久化方式");

// 批量（硅基流动支持数组输入）
List<float[]> vectors = embeddingService.embedBatch(List.of("文本1", "文本2"));
```

**4. Elasticsearch 向量存储**

ES 8.x 原生支持向量检索，不需要引入额外的向量数据库：

```json
// 索引 mapping
{
  "chunk_vector": {
    "type": "dense_vector",
    "dims": 1024,
    "similarity": "cosine",
    "index": true          // 启用 HNSW 索引
  }
}

// kNN 检索
{
  "knn": {
    "field": "chunk_vector",
    "query_vector": [0.023, -0.156, ...],
    "k": 5,
    "num_candidates": 50,
    "filter": { "term": { "knowledge_base_id": "xxx" } }
  }
}
```

**HNSW 算法原理**：
- 构建时：为每个向量建立多层跳表结构的图
- 查询时：从顶层贪心搜索，逐层细化，时间复杂度 O(log n)
- `num_candidates`：搜索时的候选集大小，越大越精确但越慢
- 权衡：精确搜索需要 O(n)，HNSW 是"近似最近邻"（ANN），牺牲少量精度换数量级的速度提升

**5. RAG 检索流程**

```java
// 1. 用户 query → 向量
float[] queryVector = embeddingService.embed("Redis持久化有哪些方式");

// 2. ES kNN 检索 → topK 相关分片
List<VectorSearchResultVO> chunks = vectorStoreService.search(query, 5, knowledgeBaseId);

// 3. 拼装 Prompt
String prompt = "基于以下参考资料回答用户问题：\n\n";
for (VectorSearchResultVO chunk : chunks) {
    prompt += "【" + chunk.getSourceFileName() + "】" + chunk.getContent() + "\n\n";
}
prompt += "用户问题：" + query;

// 4. 发送给 LLM
String answer = chatService.chat(prompt);
```

#### 面试常见问题

> **Q: RAG 和微调（Fine-tuning）的区别？**
> RAG 是"外挂知识库"，知识存在外部，每次查询动态检索；微调是把知识"烧进"模型参数。RAG 适合知识频繁更新的场景，微调适合固定领域且需要深度理解的场景。RAG 成本低、可解释性强（能标注来源），是企业级 AI 应用的首选。

> **Q: 分片策略怎么选？**
> 分片太大会超出 Token 限制且噪声多；太小会丢失上下文。1000 字符 + 200 重叠是经验值。更高级的做法是按语义分片（用 Embedding 相似度判断断点），或者按文档结构分片（标题 > 段落 > 句子）。

> **Q: 为什么用 ES 而不用专用向量数据库（Milvus/Pinecone）？**
> ES 8.x 原生支持 dense_vector + HNSW，对于中小规模（百万级文档）完全够用。优势是团队已有 ES 运维经验、不需要额外引入组件、支持混合检索（向量 + 全文检索 + 结构化过滤）。专用向量数据库在超大规模（十亿级）场景才有明显优势。

---

### 2.3 Prompt 工程（Week 3 Day 1）

#### 核心理念

Prompt 是 LLM 的"编程语言"——你用自然语言写的指令，就是模型的程序。

#### 关键技术点

**1. Prompt 模板化**

把 Prompt 从代码中抽离，变成可配置的模板：

```java
// 模板存储在 MySQL（ai_prompt_template 表）
// 变量用 {{variable}} 占位
String template = "你是{{role}}，请基于以下资料回答：\n{{context}}\n问题：{{question}}";

// 渲染
String prompt = templateEngine.render(template, variables);
```

好处：改 Prompt 不用改代码、支持 A/B 测试、非技术人员也能调整。

**2. 结构化 Prompt 设计**

```
┌─────────────────────────────────────┐
│ 系统指令（System）                    │
│   定义角色、能力边界、输出格式          │
├─────────────────────────────────────┤
│ 上下文（Context）                     │
│   RAG 检索到的相关文档片段             │
├─────────────────────────────────────┤
│ 历史对话（History）                   │
│   最近 N 轮用户和助手的对话            │
├─────────────────────────────────────┤
│ 用户问题（Query）                     │
│   当前轮次的用户输入                   │
└─────────────────────────────────────┘
```

**3. Few-shot 示例**

在 Prompt 中给出 2-3 个输入输出示例，引导模型按指定格式回答：

```
请按以下格式回答：
示例1：
问：什么是 Redis？
答：Redis 是一个开源的内存数据结构存储系统，可用作数据库、缓存和消息中间件。

示例2：
问：什么是 Docker？
答：Docker 是一个容器化平台，用于将应用及其依赖打包成标准化的容器进行部署。

现在请回答：
问：{{question}}
答：
```

#### 面试常见问题

> **Q: Temperature 参数的作用？**
> 控制输出的随机性。0 = 确定性输出（每次结果一样），1 = 高随机性。客服场景用低温（0.3）保证一致性，创意写作用高温（0.9）增加多样性。

> **Q: 如何减少 LLM 幻觉？**
> 1. RAG 注入真实数据作为上下文  2. Prompt 中明确要求"只基于提供的资料回答，不知道就说不知道"  3. 设置较低 temperature  4. 后处理：检查回答是否有来源支撑

---

### 2.4 Tool Calling — 函数调用（Week 3 Day 2）

#### 核心问题

LLM 只能"说"，不能"做"。它能告诉你"应该查询订单表"，但不能真的去查数据库。

**Tool Calling 让 LLM 能调用外部函数。**

#### 工作流程

```
用户："帮我查一下订单 ORD_001 的状态"
   ↓
LLM 分析 → 需要调用 query_order 工具，参数 {"orderId": "ORD_001"}
   ↓（模型返回 tool_calls 而非直接回答）
后端执行 → 调 MySQL 查询 → 返回 {"status": "已发货", "trackingNo": "SF123"}
   ↓（把工具结果作为 ToolMessage 发回模型）
LLM 综合 → "订单 ORD_001 已发货，快递单号 SF123"
```

#### 关键技术点

**1. 工具定义（JSON Schema）**

```json
{
  "name": "query_order",
  "description": "根据订单号查询订单详情",
  "parameters": {
    "type": "object",
    "properties": {
      "orderId": {
        "type": "string",
        "description": "订单号，如 ORD_001"
      }
    },
    "required": ["orderId"]
  }
}
```

LangChain4j 通过 `ToolSpecifications.from()` 自动生成这个 Schema。

**2. 执行模型（三阶段）**

```
阶段1: 用户消息 → 模型 → 返回 tool_calls（函数名 + 参数）
阶段2: 后端解析 tool_calls → 执行对应 Handler → 获取结果
阶段3: 工具结果作为 ToolMessage → 模型 → 自然语言回答
```

这可以多轮循环：模型可能连续调用多个工具。

**3. Handler 模式（策略模式 + 模板方法）**

```java
// 接口
public interface ToolHandler {
    String getToolName();
    String execute(Map<String, Object> params);
}

// 抽象基类 — 模板方法
public abstract class AbstractToolHandler implements ToolHandler {
    @Override
    public String execute(Map<String, Object> params) {
        validate(params);      // 参数校验（子类可覆盖）
        return doExecute(params);  // 实际执行（子类必须实现）
    }
    protected void validate(Map<String, Object> params) { /* 默认校验 */ }
    protected abstract String doExecute(Map<String, Object> params);
}

// 具体实现 — 自动注册到 Map<toolName, Handler>
@Component
public class OrderToolHandler extends AbstractToolHandler {
    @Override
    public String getToolName() { return "query_order"; }
    @Override
    protected String doExecute(Map<String, Object> params) {
        // 查数据库...
    }
}
```

**Spring 自动注入 `Map<String, ToolHandler>`**：所有 `ToolHandler` 实现类自动注册为 Map 的 value，key 是 Bean 名称。运行时根据 toolName 查找 Handler。

**4. 安全设计**

- **参数校验**：Handler 执行前校验必填参数、类型、格式
- **权限控制**：`ai_tool_role_permission` 表控制角色可调用的工具
- **审计日志**：`ai_tool_call_log` 记录每次调用的输入/输出/耗时
- **速率限制**：AOP 切面 + 令牌桶算法，防止滥用

#### 面试常见问题

> **Q: Tool Calling 和 Function Calling 有什么区别？**
> 本质相同，不同厂商叫法不同。OpenAI 叫 Function Calling，LangChain4j 叫 Tool Calling。底层都是模型输出结构化的函数调用指令，由外部代码执行。

> **Q: 如何防止 LLM 调用危险工具？**
> 1. 白名单：只暴露允许的工具定义给模型  2. 权限表：按角色控制可调用的工具  3. 参数校验：执行前校验参数合法性  4. 只读优先：查询类工具默认开放，写入类工具需要额外授权

> **Q: 多轮 Tool Calling 的消息结构？**
> ```
> SystemMessage: "你是订单助手"
> UserMessage: "查一下 ORD_001"
> AiMessage: tool_calls=[{name:"query_order", args:{orderId:"ORD_001"}}]
> ToolMessage: {result: "{status:'已发货'}"}
> AiMessage: "订单 ORD_001 已发货"
> ```
> ToolMessage 必须紧跟在触发它的 AiMessage 后面，模型才能正确关联。

---

### 2.5 线程池与并发控制（Week 2）

#### 为什么需要自定义线程池？

Spring Boot 默认用 `ForkJoinPool`，所有 @Async 任务共用一个池。文档批量解析是 CPU + IO 混合型任务，如果和其他任务共用线程池，会互相影响。

#### 自定义线程池设计

```java
ThreadPoolExecutor(
    corePoolSize    = 5,    // 核心线程：常驻，不回收
    maximumPoolSize = 10,   // 最大线程：高峰时扩容
    keepAliveTime   = 60s,  // 非核心线程空闲 60s 回收
    workQueue       = LinkedBlockingQueue(200),  // 等待队列
    rejectionPolicy = CallerRunsPolicy  // 拒绝策略：调用方线程执行
)
```

#### 拒绝策略选择

| 策略 | 行为 | 适用场景 |
|------|------|---------|
| AbortPolicy | 抛 RejectedExecutionException | 需要感知过载 |
| CallerRunsPolicy | 调用方线程执行任务 | 不想丢任务，降速处理 |
| DiscardPolicy | 静默丢弃 | 允许丢失（如日志） |
| DiscardOldestPolicy | 丢弃队列最旧的任务 | 只关心最新数据 |

文档解析选 CallerRunsPolicy：不丢任务，队列满了就让提交线程自己执行（自然限速）。

#### 监控指标

```java
pool.getActiveCount();      // 活跃线程数
pool.getPoolSize();         // 当前线程池大小
pool.getQueue().size();     // 队列积压数
pool.getCompletedTaskCount(); // 已完成任务数
```

暴露为 HTTP 接口 + 前端仪表盘，实时监控线程池健康状态。

#### 面试常见问题

> **Q: 线程池参数怎么设？**
> CPU 密集型：核心线程数 = CPU 核数 + 1。IO 密集型：核心线程数 = CPU 核数 × 2（或更高，因为线程大部分时间在等 IO）。实际需要压测调优，没有万能公式。

> **Q: 队列满了怎么办？**
> 三种策略：1. 扩容线程（到 maximumPoolSize）  2. 扩容队列（但会增加延迟）  3. 拒绝（CallerRunsPolicy 或抛异常）。生产环境建议先 CallerRunsPolicy 兜底，再配合监控告警。

---

## 三、架构设计能力总结

### 3.1 分层架构

```
Controller（接口层）→ Service（业务层）→ Mapper/外部API（数据层）
     ↓                    ↓                     ↓
  参数校验            业务编排              数据持久化
  异常处理            事务管理              外部调用
  日志记录            缓存策略              连接池管理
```

### 3.2 设计模式应用

| 模式 | 应用场景 | 实现方式 |
|------|---------|---------|
| 策略模式 | 多模型切换、Tool Handler 分发 | 接口 + Map 注入 |
| 模板方法 | Tool Handler 的 validate → execute | AbstractToolHandler |
| 观察者模式 | 流式响应的 Listener 回调 | StreamingChatModelListener |
| 建造者模式 | VO 对象构造 | Lombok @Builder |
| 工厂模式 | RestTemplate Bean 按用途分离 | @Bean + @Qualifier |

### 3.3 工程实践

- **配置外置**：所有模型参数、API Key 都在 YAML 中，换模型不改代码
- **接口幂等**：ES 用 chunk_id 作为 _id，重复向量化不会产生重复数据
- **优雅降级**：Embedding 超时时返回零向量，检索结果为空但不报错
- **资源隔离**：不同外部服务用不同 RestTemplate，超时/认证互不影响
- **可观测性**：线程池监控 + 模型调用日志 + 工具调用审计

---

## 四、技术深度亮点（面试加分项）

### 4.1 向量检索的工程细节

- 理解 HNSW 算法原理（多层跳表 + 贪心搜索）
- 理解 `num_candidates` 对精度和性能的影响
- 理解余弦相似度 vs 欧氏距离的适用场景
- 能解释为什么 kNN 返回的 `_score` 在 0~1 之间（cosine similarity）

### 4.2 RAG 的优化方向

- **分片策略**：固定长度 vs 递归分割 vs 语义分片
- **检索优化**：混合检索（向量 + BM25 全文检索）、Rerank 重排序
- **上下文窗口**：Token 计算、消息截断策略、滑动窗口
- **评估指标**：Faithfulness（忠实度）、Relevancy（相关性）、Answer Correctness

### 4.3 Tool Calling 的安全考量

- 工具定义与执行分离：模型只看到 Schema，不接触实现
- 参数注入防护：所有参数经过校验后才传入 SQL
- 调用链审计：完整的输入/输出/耗时日志
- 权限最小化：按角色开放工具，不是所有用户都能调所有工具

### 4.4 生产级关注点

- **超时设计**：每个外部调用都有独立超时（Embedding 30s、ES 30s、Parser 120s）
- **重试策略**：指数退避（1s → 2s → 4s），避免雪崩
- **背压控制**：线程池队列 + CallerRunsPolicy，防止请求堆积
- **资源释放**：SSE 连接 complete/error 都要清理，避免连接泄漏

---

## 五、技术栈能力矩阵

| 能力维度 | 掌握程度 | 体现 |
|---------|---------|------|
| Spring Boot 3 | ★★★★★ | 自定义配置、多 Bean 管理、AOP、异步 |
| MyBatis-Plus | ★★★★☆ | 实体映射、分片查询、条件构造 |
| LangChain4j | ★★★★☆ | 模型抽象、流式调用、Tool Calling、Embedding |
| Elasticsearch | ★★★★☆ | dense_vector、kNN、Bulk API、索引管理 |
| 并发编程 | ★★★★☆ | 线程池调优、CompletableFuture、背压控制 |
| Prompt 工程 | ★★★☆☆ | 模板化、Few-shot、结构化 Prompt |
| 前后端联调 | ★★★★☆ | SSE 流式、Vue 3 响应式、API 对接 |
| 系统设计 | ★★★★☆ | 分层架构、设计模式、容错降级 |

---

## 六、一句话总结

> 独立完成了一套从"文档上传 → 解析分片 → 向量化 → 检索增强 → 模型对话 → 工具调用"的完整 AI 应用链路，覆盖 RAG、Prompt Engineering、Tool Calling 三大核心能力，具备企业级 AI 应用开发的全栈能力。
