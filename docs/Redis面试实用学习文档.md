# Redis 面试实用学习文档

> 适合 3-5 年 Java 工程师面试冲刺。目标不是只会 `set/get`，而是能把 Redis 的数据结构、单线程模型、持久化、高可用、缓存设计、一致性与线上排查讲清楚，并落到真实业务场景。

![Redis 面试知识地图](images/redis-01-core-map.svg)

## 先看一个直观示例：商品详情缓存 + 防击穿

假设商品详情接口访问量很高，如果每次都查 MySQL，热点商品会把数据库打得很难受。Redis 在这里的作用是：**把热点读请求挡在内存层，同时用互斥重建避免缓存失效瞬间大量请求打到数据库**。

```java
@Service
public class ProductQueryService {

    private static final String PRODUCT_KEY = "product:detail:";
    private static final String LOCK_KEY = "lock:product:detail:";

    private final StringRedisTemplate redisTemplate;
    private final ProductMapper productMapper;
    private final ObjectMapper objectMapper;

    public ProductDTO getProduct(Long productId) throws Exception {
        String cacheKey = PRODUCT_KEY + productId;
        String json = redisTemplate.opsForValue().get(cacheKey);
        if (json != null) {
            if (json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, ProductDTO.class);
        }

        String lockKey = LOCK_KEY + productId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(5));

        if (Boolean.TRUE.equals(locked)) {
            try {
                ProductDTO product = productMapper.selectById(productId);
                if (product == null) {
                    redisTemplate.opsForValue().set(cacheKey, "", Duration.ofMinutes(5));
                    return null;
                }
                redisTemplate.opsForValue().set(
                        cacheKey,
                        objectMapper.writeValueAsString(product),
                        Duration.ofMinutes(30).plusSeconds(ThreadLocalRandom.current().nextInt(60))
                );
                return product;
            } finally {
                redisTemplate.delete(lockKey);
            }
        }

        Thread.sleep(50);
        return getProduct(productId);
    }
}
```

这个例子里 Redis 做了几件事：

1. 热点数据缓存，降低数据库压力。
2. 空值缓存，缓解缓存穿透。
3. TTL 加随机值，降低雪崩风险。
4. 互斥重建，缓解热点 key 击穿。

生产上还要继续增强：分布式锁释放要校验 value，递归重试要改成有限循环，热点商品可以使用逻辑过期 + 后台异步刷新。

## 目录

- [一、Redis 面试主线](#一redis-面试主线)
- [二、Redis 数据类型与真实业务场景](#二redis-数据类型与真实业务场景)
- [三、底层编码要点](#三底层编码要点)
- [四、为什么 Redis 快](#四为什么-redis-快)
- [五、持久化：RDB、AOF、混合持久化](#五持久化rdbaof混合持久化)
- [六、高可用：主从、哨兵、集群](#六高可用主从哨兵集群)
- [七、缓存设计与一致性问题](#七缓存设计与一致性问题)
- [八、分布式锁、限流与消息场景](#八分布式锁限流与消息场景)
- [九、高级用法与工程实践](#九高级用法与工程实践)
- [十、常见线上问题与排查](#十常见线上问题与排查)
- [十一、面试高频回答模板](#十一面试高频回答模板)

---

## 一、Redis 面试主线

四年 Java 工程师面试 Redis，常见追问链路通常是：

```text
为什么要用 Redis
  -> Redis 为什么快
  -> 你用过哪些数据结构
  -> 缓存击穿/穿透/雪崩怎么处理
  -> Redis 持久化怎么选
  -> 主从/哨兵/集群区别
  -> 分布式锁靠谱吗
  -> 大 key、热 key、阻塞、内存淘汰怎么排查
```

面试官真正想听的是三件事：

1. 你理解 Redis 不是停留在 API 层。
2. 你知道缓存不是"放进去就完事"，而是一个一致性和稳定性工程问题。
3. 你遇到线上问题时，知道该看哪些指标、哪些命令、哪些根因。

---

## 二、Redis 数据类型与真实业务场景

Redis 对外暴露 5 种基础类型 + 4 种扩展类型，面试的关键不是背命令，而是能说清"这个结构在什么业务下用、为什么不用别的"。

### 2.1 String — 缓存 / 计数 / 分布式锁

最万能的类型。存 JSON 做缓存、`INCR` 做原子计数、`SETNX + PX` 做分布式锁。

**场景：短信验证码，60 秒过期，防重发**

```java
// 存验证码，60s 自动过期
redisTemplate.opsForValue().set("sms:code:" + phone, code, 60, TimeUnit.SECONDS);

// 校验
String cached = redisTemplate.opsForValue().get("sms:code:" + phone);
if (!code.equals(cached)) throw new BizException("验证码错误或已过期");
```

**场景：接口限流 — 固定窗口**

```java
String key = "rate:limit:" + userId;
Long count = redisTemplate.opsForValue().increment(key);
if (count == 1) redisTemplate.expire(key, 1, TimeUnit.MINUTES);
if (count > 100) throw new BizException("请求过于频繁");
```

### 2.2 Hash — 对象局部读写

适合存"字段多但每次只改几个"的对象，比整个 JSON 覆盖更省带宽、更安全。

**场景：用户 Profile 局部更新**

```java
String key = "user:profile:" + userId;
// 只改昵称和头像，不碰其他字段
Map<String, String> updates = Map.of("nickname", "阿飞", "avatar", "https://cdn/a.png");
redisTemplate.opsForHash().putAll(key, updates);

// 读取单个字段
String nickname = (String) redisTemplate.opsForHash().get(key, "nickname");
```

对比 String 存 JSON：每次改一个字段都要反序列化→改→序列化→整体覆盖，Hash 只动目标字段。

### 2.3 List — 消息队列 / 时间线

有序、可重复、支持两端操作。`LPUSH + BRPOP` 可实现轻量阻塞队列。

**场景：简易异步任务队列**

```java
// 生产者：投递任务
redisTemplate.opsForList().leftPush("task:queue", taskJson);

// 消费者：阻塞取任务，超时 5s
String task = redisTemplate.opsForList().rightPop("task:queue", 5, TimeUnit.SECONDS);
if (task != null) processTask(task);
```

**场景：最新评论时间线（保留最近 200 条）**

```java
redisTemplate.opsForList().leftPush("comment:timeline:" + articleId, commentJson);
redisTemplate.opsForList().trim("comment:timeline:" + articleId, 0, 199);
```

### 2.4 Set — 去重 / 标签 / 交并差

无序、唯一。天然适合"判断在不在"和"求交集"。

**场景：文章点赞 + 判断是否已赞**

```java
String key = "article:likes:" + articleId;
redisTemplate.opsForSet().add(key, userId);        // 点赞
redisTemplate.opsForSet().remove(key, userId);     // 取消点赞
Boolean liked = redisTemplate.opsForSet().isMember(key, userId); // 是否已赞
Long likeCount = redisTemplate.opsForSet().size(key);            // 点赞总数
```

**场景：共同关注（两个用户关注集合取交集）**

```java
Set<String> common = redisTemplate.opsForSet().intersect(
    "user:following:" + userIdA,
    "user:following:" + userIdB
);
```

### 2.5 ZSet（Sorted Set）— 排行榜 / 延迟队列

score 排序 + member 唯一，是排行榜的标配结构。

**场景：实时热搜排行榜**

```java
String key = "hot:search:rank";
// 用户每搜一次，score +1
redisTemplate.opsForZSet().incrementScore(key, keyword, 1);

// 取 Top 10（score 从高到低）
Set<ZSetOperations.TypedTuple<String>> top10 =
    redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, 9);

// 查某关键词排名（0-based，需要 +1）
Long rank = redisTemplate.opsForZSet().reverseRank(key, keyword);
```

**场景：延迟队列 — 订单超时取消**

```java
// score = 过期时间戳
redisTemplate.opsForZSet().add("order:delay:queue", orderId, expireTimestamp);

// 定时扫描：取出所有已到期的订单
Set<String> expiredOrders = redisTemplate.opsForZSet()
    .rangeByScore("order:delay:queue", 0, System.currentTimeMillis());
// 处理后移除
expiredOrders.forEach(id ->
    redisTemplate.opsForZSet().remove("order:delay:queue", id));
```

### 2.6 Bitmap — 状态压缩

本质还是 String，但按 bit 操作。适合"连续日期 + 布尔状态"场景，内存极省。

**场景：用户每日签到，一年只占 365 bit ≈ 46 字节**

```java
String key = "user:checkin:" + userId + ":" + year;
int dayOfYear = LocalDate.now().getDayOfYear();

redisTemplate.opsForValue().setBit(key, dayOfYear, true);   // 签到
boolean checked = redisTemplate.opsForValue().getBit(key, dayOfYear); // 今日是否已签

// 本月签到天数
long monthDays = redisTemplate.execute((RedisCallback<Long>) conn ->
    conn.bitCount(key.getBytes(), startOffset, endOffset));
```

### 2.7 HyperLogLog — 近似去重计数

误差约 0.81%，但内存固定 12KB。适合亿级 UV 统计这种"不需要精确到个位"的场景。

**场景：页面 UV 统计**

```java
String key = "page:uv:" + date;
redisTemplate.opsForHyperLogLog().add(key, userId);   // 每次访问丢进去
Long uv = redisTemplate.opsForHyperLogLog().size(key); // 估算 UV 数
```

对比 Set：1 亿用户 Set 要几百 MB，HyperLogLog 只要 12KB。

### 2.8 GEO — 地理位置

存经纬度，支持距离计算和范围查询。

**场景：附近门店**

```java
// 录入门店坐标
redisTemplate.opsForGeo().add("store:geo",
    new Point(116.397128, 39.916527), "朝阳店");
redisTemplate.opsForGeo().add("store:geo",
    new Point(116.481050, 39.908715), "国贸店");

// 查 3km 内的门店
GeoResults<RedisGeoCommands.GeoLocation<String>> nearby =
    redisTemplate.opsForGeo().radius("store:geo",
        new Circle(new Point(lng, lat),
            new Distance(3, RedisGeoCommands.DistanceUnit.KILOMETERS)));
```

### 2.9 Stream — 消息流 / 消费组

Redis 5.0 引入，支持消费组和消息确认，是 Redis 里最接近消息队列的结构。

**场景：轻量订单消费组**

```java
// 生产
Map<String, String> msg = Map.of("orderId", "10001", "action", "pay");
redisTemplate.opsForStream()
    .add(StreamRecords.string(msg).withStreamKey("order:stream"));

// 创建消费组（只需一次）
redisTemplate.opsForStream().createGroup("order:stream", "order-group");

// 消费
List<MapRecord<String, String, String>> records = redisTemplate.opsForStream().read(
    Consumer.from("order-group", "consumer-1"),
    StreamReadOptions.empty().count(10).block(Duration.ofSeconds(3)),
    StreamOffset.create("order:stream", ReadOffset.lastConsumed()));

// 确认
records.forEach(r -> redisTemplate.opsForStream()
    .acknowledge("order:stream", "order-group", r.getId()));
```

> 如果已有 RocketMQ / Kafka，Stream 通常做补位（轻量异步、临时消费），不替代专业 MQ。

---

## 三、底层编码要点

面试不需要背所有编码名字，但要理解"同一个逻辑类型，数据量不同，底层实现不同"。

### 3.1 编码转换核心思路

| 逻辑类型 | 数据量小时 | 数据量大时 | 关键点 |
| --- | --- | --- | --- |
| String | `int`（纯数字）/ `embstr`（短串） | `raw`（SDS） | embstr 一次 malloc，raw 两次 |
| Hash | `listpack`（紧凑数组） | `hashtable` | 阈值由 `hash-max-listpack-entries/size` 控制 |
| List | `listpack` | `quicklist`（listpack + 双向链表） | 两端操作 O(1)，中间操作 O(N) |
| Set | `intset`（纯整数）/ `listpack` | `hashtable` | intset 升序数组，二分查找 |
| ZSet | `listpack` | `skiplist + hashtable` | 跳表保序，字典 O(1) 查分 |

### 3.2 面试该怎么说

三个要点够了：

1. **Redis 不是简单的 Map**——同一个类型在不同数据规模下编码不同，内存和性能特征也不同。
2. **编码切换有阈值**——比如 Hash 默认 128 个字段或 64 字节以内用 listpack，超了切 hashtable。生产上可以调大阈值来省内存。
3. **ZSet 为什么用跳表不用红黑树**——跳表实现简单、范围查询天然友好（链表顺序遍历）、并发友好。

### 3.3 一个容易踩的坑

如果你往 Hash 里存了 200 个字段，它会从 listpack 切成 hashtable，内存瞬间膨胀。如果你知道这个对象字段不会太多，可以适当调大 `hash-max-listpack-entries`，让它留在紧凑编码里。反过来说，如果你发现某个 Hash 内存异常大，先看看是不是编码已经切了 hashtable。

---

## 四、为什么 Redis 快

### 4.1 常规回答太浅

很多人只会说：

1. 基于内存
2. 单线程避免锁
3. IO 多路复用

这三句没错，但还不够。

### 4.2 更完整的解释

Redis 快，核心是几个因素叠加：

1. **数据在内存中**
2. **绝大多数命令是 O(1) 或接近 O(1)**
3. **单线程执行命令避免了多线程竞争和上下文切换**
4. **网络层使用 IO 多路复用，提高连接处理效率**
5. **协议简单，序列化负担小**
6. **内部对象设计和内存布局比较激进**

### 4.3 单线程到底指什么

这里的"单线程"主要指：

- **命令执行主线程是单线程模型**

不代表 Redis 完全没有其他线程。现代 Redis 在持久化、异步删除、网络读写辅助等方面会用到后台线程。

所以更准确的表达是：

> Redis 的核心命令执行路径是单线程串行执行，这保证了绝大多数操作的原子性和实现简单性；而一些非核心路径，如持久化重写、异步回收等，会借助后台线程处理。

### 4.4 IO 多路复用怎么理解

本质上是：

- 一个线程监听多个 socket
- 哪个连接可读/可写，就处理哪个
- 避免每个连接一个线程

这让 Redis 在高并发短请求场景下非常高效。

---

## 五、持久化：RDB、AOF、混合持久化

Redis 数据在内存里，进程一挂数据就没了。持久化就是把内存数据落盘的手段，有两种基本方式 + 一种组合方式。

### 5.1 RDB（快照）

RDB 的思路很直接：**在某个时间点把内存里的全量数据dump成一个紧凑的二进制文件**（默认 `dump.rdb`）。

触发方式有三种：`save`（阻塞主进程，生产别用）、`bgsave`（fork 子进程，主进程继续服务）、`shutdown`（关进程时自动触发）。

优点：文件小、恢复速度快、适合定期备份和异地容灾。

缺点：两次快照之间的写操作可能丢失。如果你设了 `save 900 1`（900 秒内至少 1 次写入才触发），那最坏情况可能丢 15 分钟数据。

### 5.2 AOF（追加日志）

AOF 的思路是：**把每一条写命令追加到日志文件末尾**（默认 `appendonly.aof`），恢复时重放所有命令。

AOF 文件会随着写入不断膨胀，所以引入了 **AOF rewrite（重写）**：Redis fork 一个子进程，根据当前内存数据重新生成一份最小命令集，替换掉旧文件。比如对同一个 key 做了 100 次 SET，重写后只保留最后那一条。

优点：数据更完整，最多丢 1 秒（`everysec` 策略下）。

缺点：文件比 RDB 大，恢复速度比 RDB 慢（要逐条重放命令）。

### 5.3 fork 到底干了什么

这是面试高频追问点。不管是 `bgsave` 还是 AOF rewrite，Redis 都走同一条路：

```text
主进程收到持久化指令
  → 调用 Linux fork() 创建子进程
  → 子进程拿到父进程内存的"完整副本"
  → 子进程在副本上做快照/重写，写盘
  → 主进程继续处理客户端请求，互不干扰
```

这里的核心机制是操作系统的 **Copy-On-Write（写时复制，COW）**：

fork 的瞬间，子进程并不真的复制一份完整内存，而是和父进程**共享同一份物理页**，页表标记为只读。只有当父进程要写某一页时，OS 才会把这一页复制一份给父进程去改，子进程继续读原来的。

这意味着：

**fork 本身的代价** — fork 需要复制页表（不是复制数据页）。如果 Redis 占 10GB 内存，页表大约几十 MB，fork 瞬间主进程会阻塞几毫秒到几十毫秒。内存越大，页表越大，阻塞越久。这就是为什么**大实例 fork 会造成短时延迟抖动**。

**COW 的内存代价** — fork 之后，如果主进程大量写入，被修改的页都会被复制一份。极端情况下（主进程在 bgsave 期间把全部内存页都写了一遍），实际内存占用会接近原来的 2 倍。这就是生产上 Redis 内存利用率不建议超过 50%-60% 的原因之一——要给 COW 留余量。

**实际影响总结：**

| 影响 | 表现 | 应对 |
| --- | --- | --- |
| fork 阻塞 | 主进程短时无法处理请求（ms 级） | 避免单实例内存过大，建议 < 10GB |
| COW 内存膨胀 | 物理内存可能翻倍 | 预留内存余量，`maxmemory` 不要设太满 |
| 磁盘 IO | 子进程写盘可能和主进程抢 IO | 独立磁盘或 SSD，`renice` 降低子进程优先级 |

> 面试这样说：RDB 和 AOF rewrite 都依赖 fork + COW，fork 的阻塞和 COW 的内存膨胀是大实例持久化的核心风险，所以生产上一般控制单实例内存，并用 `maxmemory-policy` 和合理的内存预留来兜底。

### 5.4 `appendfsync` 三种策略到底在控制什么

AOF 写入分两步理解：

```text
写命令 → 追加到 AOF 缓冲区（aof_buf，用户态内存）
       → fsync 刷到磁盘（内核页缓存 → 磁盘）
```

`appendfsync` 控制的就是**第二步的刷盘频率**：

| 策略 | 行为 | 性能 | 数据安全 | 适用场景 |
| --- | --- | --- | --- | --- |
| `always` | 每条写命令都立即 `fsync` 到磁盘 | 最差，TPS 可能掉到几百 | 最高，不丢数据 | 金融级强一致，极少用 |
| `everysec` | 每秒由后台线程批量 `fsync` 一次 | 好，接近无持久化 | 最多丢约 1 秒数据 | **生产最常用** |
| `no` | 不主动 `fsync`，交给 OS 自己决定（通常 30 秒） | 最好 | 风险最大，OS 崩溃可能丢大量数据 | 纯缓存、允许丢数据 |

补充一个细节：`everysec` 模式下，如果 `fsync` 还没完成又到了下一秒，Redis 默认会**阻塞等待上一次 fsync 完成**再继续写入（由 `aof-delay-fsync` 控制，默认 0 即不延迟等待）。这意味着一次慢磁盘 IO 可能拖慢整个 Redis。

> 面试这样说：`appendfsync` 本质控制的是 AOF 缓冲区到磁盘的刷盘频率。`always` 每条都刷，最安全但最慢；`everysec` 每秒刷一次，是性能和安全的平衡点；`no` 交给操作系统，性能最好但宕机可能丢数据。大多数业务选 `everysec` 就够了。

### 5.5 混合持久化

Redis 4.0+ 引入，核心思路：**AOF rewrite 时，前半段用 RDB 格式写快照，后半段追加增量 AOF 命令**。

这样得到的文件既是合法 AOF 文件（Redis 能识别），又享受了 RDB 恢复快的优势。恢复时先加载 RDB 部分（快），再重放后面的 AOF 增量命令（少），整体恢复速度远优于纯 AOF。

开启方式：`aof-use-rdb-preamble yes`（Redis 5.0+ 默认开启）。

### 5.6 生产环境怎么选

| 场景 | 推荐方案 | 理由 |
| --- | --- | --- |
| 纯缓存，数据可重建 | 关闭持久化，或只开 RDB | 省 IO，恢复不重要 |
| 一般业务缓存 + 状态 | AOF `everysec` + 混合持久化 | 兼顾恢复速度和安全 |
| 高价值状态（分布式锁、会话） | AOF `everysec` + 定期 RDB 备份 | 双保险，RDB 用于异地容灾 |

不管哪种方案，都要注意 fork 的代价。单实例内存越大，fork 阻塞越久、COW 膨胀越严重。这也是为什么生产上通常把单实例控制在 10GB 以内，大数据量用 Cluster 分片来分散。

---

## 六、高可用：主从、哨兵、集群

### 6.1 主从复制

主从复制的核心目的有三个：读扩展（把读请求分散到从节点）、数据冗余（从节点持有完整数据副本）、为哨兵和集群的故障切换提供数据基础。

#### 复制流程：全量同步 vs 增量同步

主从复制分两个阶段：

**全量同步（首次连接 / 断线太久）：**

```text
从节点发送 PSYNC ? -1
  → 主节点执行 BGSAVE 生成 RDB 快照
  → 快照发送给从节点
  → 从节点加载 RDB 到内存
  → 在此期间主节点的写命令缓存到 repl_backlog
  → 快照加载完成后，把 repl_backlog 里的增量命令补发给从节点
  → 从节点追上主节点，进入增量同步阶段
```

全量同步的代价很大：主节点要 fork、要生成 RDB、要通过网络传输。如果 Redis 实例内存 10GB，一次全量同步可能要传输几 GB 数据，对网络和磁盘都是压力。

**增量同步（正常 / 短暂断线恢复）：**

```text
从节点发送 PSYNC <runid> <offset>
  → 主节点检查 runid 是否一致、offset 是否在 repl_backlog 范围内
  → 如果匹配，只补发 offset 之后的增量命令
  → 从节点追上，恢复同步
```

增量同步几乎无代价，但前提是：断线时间不能太长，否则 `repl_backlog` 里的旧命令已经被覆盖（默认 backlog 只有 1MB，可调大）。

#### 面试关键点

1. **主从默认异步复制**——主节点写完内存就返回客户端成功，不等从节点确认。这意味着主节点崩溃时，从节点可能还没收到最新写入，切换后会丢数据。
2. **`WAIT` 命令可以做同步复制**——`WAIT 2 1000` 表示等待至少 2 个从节点确认收到写入，最多等 1000ms。但它也不是强一致（只是"等确认"，不是事务提交），生产用得不多。
3. **读写分离的坑**——刚写完主节点，马上去从节点读，可能读到旧值（主从延迟）。对一致性要求高的读请求应该读主节点。

### 6.2 哨兵 Sentinel

哨兵解决的是：**主从架构下，主节点挂了怎么办？** 没有哨兵，你得半夜爬起来手动切换主从。

#### 哨兵的三个职责

1. **监控**：哨兵集群定期 PING 主从节点，判断是否存活。
2. **自动故障转移**：主节点不可达时，哨兵集群投票选出一个从节点提升为新主。
3. **通知**：故障转移完成后，哨兵通过 Pub/Sub 通知客户端新主地址。

#### 故障判定：主观下线 vs 客观下线

```text
单个哨兵 PING 主节点超时
  → 标记为主观下线（SDOWN，Subjectively Down）
  → 向其他哨兵询问：你们觉得主节点活着吗？
  → 如果 quorum 个哨兵都说不可达
  → 标记为客观下线（ODOWN，Objectively Down）
  → 触发故障转移流程
```

`quorum` 是配置参数，比如 `sentinel monitor mymaster 127.0.0.1 6379 2` 中的 `2`，表示至少 2 个哨兵同意才认为主节点真的挂了。这个值设太小容易误判，设太大又可能导致无法达成多数。

#### 故障转移流程

```text
1. 哨兵集群选举一个 Leader 哨兵（Raft 算法）
2. Leader 从从节点中选一个最合适的提升为新主：
   - 优先级最高的从节点
   - 复制偏移量最大（数据最新）的从节点
   - runid 字典序最小的
3. Leader 让选中的从节点执行 SLAVEOF NO ONE，变成新主
4. 让其他从节点 SLAVEOF 新主
5. 通过 Pub/Sub 通知客户端
6. 旧主恢复后自动变成从节点
```

#### 哨兵不是分片方案

哨兵只有一主多从的拓扑，所有节点存的数据是一样的。它解决的是"主挂了自动切"，不能水平扩展写能力和存储容量。如果需要分片，得上 Cluster。

### 6.3 Cluster

Redis Cluster 解决两个问题：**数据分片**（水平扩展写和存储）和**节点故障转移**（每个主节点可以带从节点）。

#### 核心概念：16384 个槽

```text
Cluster 把所有数据分成 16384 个哈希槽（slot）
  → 每个 key 通过 CRC16(key) % 16384 算出槽号
  → 每个槽分配给一个主节点
  → 比如 3 主节点，每人分约 5461 个槽
```

客户端连接任意一个节点都能发命令。如果命令对应的 key 不在当前节点的槽里，节点返回 `MOVED 槽号 目标节点地址`，客户端跟着跳转。

#### Gossip 协议与故障检测

Cluster 节点之间通过 Gossip 协议互相通信：

```text
每个节点定期随机选几个其他节点，交换 PING/PONG 和状态信息
  → 如果某节点长时间 PONG 没收到
  → 标记为 PFAIL（Possible Fail，类似主观下线）
  → 当多数主节点都标记该节点为 PFAIL
  → 转为 FAIL（确定下线）
  → 该节点的从节点发起选举，获得多数票后提升为新主
```

#### 槽迁移与扩缩容

增加节点时，需要把一部分槽从旧节点迁移到新节点。迁移过程是渐进的：

```text
1. 目标节点向源节点请求接管某些槽
2. 源节点标记这些槽为 MIGRATING 状态
3. 目标节点标记为 IMPORTING 状态
4. 逐 key 执行 MIGRATE 命令，把数据从源节点搬到目标节点
5. 所有 key 迁完后，通知集群更新槽映射
```

迁移期间，如果客户端访问正在迁移的 key，节点返回 `ASK` 重定向，客户端先向目标节点发 `ASKING` 命令，再发实际操作。

### 6.4 Cluster 的工程注意点

**多 key 操作必须在同一槽：** Cluster 不支持跨槽的多 key 操作（比如 `MGET key1 key2`，如果 key1 和 key2 不在同一槽，会报错）。解决方法是用 `{hashtag}` 强制让相关 key 落在同一槽：

```text
订单相关 key 统一用 {user100}:order:xxx 格式
  → CRC16 只对 {} 内的部分计算
  → 所有 user100 的订单 key 都落在同一槽
```

**Lua 脚本同理：** 脚本里涉及的 key 必须在同一槽，否则执行失败。

**大 key 和热 key 问题依然存在：** 即使做了分片，如果某个 key 特别大或特别热，它所在的那个槽对应的那个节点会承受所有压力。Cluster 解决的是数据分散，不解决单点热点。

### 6.5 主从、哨兵、集群对比

| 维度 | 主从 | 哨兵 | 集群 |
| --- | --- | --- | --- |
| 解决什么问题 | 读扩展、数据冗余 | 故障自动切换 | 分片 + 高可用 |
| 数据是否分片 | 否（全量副本） | 否（全量副本） | 是（16384 槽） |
| 写能力扩展 | 不能 | 不能 | 能 |
| 故障自动切换 | 不能（手动） | 能 | 能 |
| 典型部署 | 1主 N从 | 1主 N从 + 3哨兵 | 3主 3从（最少） |
| 适用场景 | 读多写少、简单缓存 | 需要自动故障转移 | 大数据量、高写入 |

> 面试这样说：主从是基础拓扑，解决读扩展和冗余；哨兵在主从上加了自动故障检测和切换，但不解决写扩展；Cluster 通过 16384 个槽做数据分片，同时支持每个槽的主从切换，是真正能水平扩展的方案。三者是递进关系，不是替代关系。

---

## 七、缓存设计与一致性问题

![Redis Cache Aside 一致性流程](images/redis-02-cache-consistency.svg)

### 7.1 缓存三大经典问题

#### 缓存穿透

穿透是指查询根本不存在的数据，请求绕过缓存直接打到数据库。比如有人用脚本遍历 `userId = -1, -2, -3...` 去查用户信息，数据库里根本没有这些 ID，缓存里也不会有，所有请求都穿透到 DB。

**解法一：空值缓存**

```java
public UserDTO getUser(Long userId) {
    String key = "user:" + userId;
    String json = redis.get(key);
    if (json != null) {
        return json.isEmpty() ? null : parse(json);
    }
    
    UserDTO user = db.selectById(userId);
    if (user == null) {
        // 缓存空值，但 TTL 设短一些（比如 2-5 分钟）
        redis.set(key, "", Duration.ofMinutes(3));
        return null;
    }
    
    redis.set(key, toJson(user), Duration.ofMinutes(30));
    return user;
}
```

空值缓存简单直接，但如果攻击方用大量不同的非法 key 来打，缓存里会塞满空值，浪费内存。

**解法二：布隆过滤器**

```text
启动时把所有合法 userId 灌入布隆过滤器（占内存极小，1亿ID约120MB）
  → 查询前先问布隆过滤器：这个 userId 存在吗？
  → 布隆说"不存在" → 直接返回，不查缓存也不查 DB
  → 布隆说"可能存在" → 正常走缓存→DB 流程
```

布隆过滤器有误判率（可能把不存在的判为存在，但不会把存在的判为不存在），一般设 1%-3% 就够用。它能挡住绝大多数穿透请求。

**解法三：参数校验前置**

在进入缓存逻辑之前就校验参数合法性：`userId <= 0` 直接拒绝，`pageSize > 100` 直接拒绝。这是最廉价的第一道防线。

#### 缓存击穿

击穿是指某个热点 key 恰好过期的瞬间，大量并发请求同时发现缓存 miss，全部打到数据库。比如一个爆款商品详情页缓存到期了，每秒几万请求同时去查 DB。

**解法一：互斥锁重建（开头示例用的就是这个）**

```java
// 缓存 miss 后，只让一个线程去查 DB 重建缓存
String lockKey = "lock:" + cacheKey;
boolean locked = redis.set(lockKey, "1", NX, EX, 5);
if (locked) {
    try {
        // 双重检查：拿到锁后先看缓存是否已被别人重建
        String recheck = redis.get(cacheKey);
        if (recheck != null) return parse(recheck);
        
        // 真正去查 DB 并回填缓存
        Object data = db.query(...);
        redis.set(cacheKey, toJson(data), EX, ttl);
        return data;
    } finally {
        redis.del(lockKey);
    }
}
// 没拿到锁的线程：短暂等待后重试
Thread.sleep(50);
return retryWithLimit(cacheKey, maxRetry);
```

互斥锁保证了同一时刻只有一个线程去查 DB，其他线程等待或重试。缺点是等待的线程会占用连接池资源。

**解法二：逻辑过期（永不过期 + 异步刷新）**

```java
// key 不设物理 TTL，而是在 value 里存一个逻辑过期时间
String json = redis.get(cacheKey);
DataWrapper wrapper = parse(json);

if (wrapper.isExpired()) {
    // 缓存"逻辑过期"了，但不是所有线程都去重建
    boolean locked = redis.set(lockKey, "1", NX, EX, 10);
    if (locked) {
        // 异步线程池去重建，当前线程先返回旧数据
        executor.submit(() -> {
            try {
                Object fresh = db.query(...);
                redis.set(cacheKey, new DataWrapper(fresh, ttl), EX, 0);
            } finally {
                redis.del(lockKey);
            }
        });
    }
    // 不管拿没拿到锁，当前请求都先返回旧数据
    return wrapper.getData();
}
return wrapper.getData();
```

逻辑过期的好处是：用户不会因为缓存重建而被阻塞（总是能拿到数据，哪怕是旧的）。缺点是有一小段时间大家看到的是过期数据。

**解法三：热点 key 永不过期**

对确认是热点的数据（比如首页配置、爆款商品），直接不设 TTL，由后台定时任务主动刷新。

#### 缓存雪崩

雪崩是指大量 key 同时过期，或者 Redis 整体宕机，导致所有请求同时打到数据库。

**解法一：TTL 加随机值**

```java
// 基础 TTL 30 分钟 + 随机 0-60 秒
Duration ttl = Duration.ofMinutes(30).plusSeconds(ThreadLocalRandom.current().nextInt(60));
redis.set(cacheKey, data, ttl);
```

这样即使同一批写入的 key，过期时间也会被分散开，避免同时失效。

**解法二：多级缓存**

```text
请求 → L1 本地缓存（Caffeine，毫秒级，容量小）
     → L2 Redis 缓存（微秒级，容量大）
     → L3 数据库
```

即使 Redis 整体不可用，本地缓存还能扛一波。常用组合是 Caffeine（本地）+ Redis（远程），配合 Spring Cache 的 `CaffeineCacheManager` + `RedisCacheManager` 实现。

**解法三：限流降级 + Redis 高可用**

在应用层做限流（比如 Sentinel），超过阈值直接返回兜底数据或友好提示。同时 Redis 本身要做高可用（哨兵或 Cluster），避免单点故障引发全局雪崩。

### 7.2 一致性不要回答成"绝对一致"

Redis + MySQL 的缓存一致性，本质上多数业务只能做到**最终一致**。追求强一致需要分布式事务（2PC、TCC），代价太大，绝大多数互联网业务不需要也不应该这么做。

面试时把"最终一致"说清楚就够：

> 在缓存和数据库共存的架构下，我们通常接受短时间内的数据不一致（比如几百毫秒到几秒），通过合理的读写策略和 TTL 来缩小这个不一致窗口，而不是追求绝对的实时一致。

### 7.3 Cache Aside 详解

Cache Aside 是工程上最常用的缓存一致性策略，流程如下：

**读路径：**

```text
1. 查缓存 → 命中 → 直接返回
2. 未命中 → 查数据库 → 写入缓存 → 返回
```

**写路径：**

```text
1. 先更新数据库
2. 再删除缓存（注意是删除，不是更新缓存）
```

#### 为什么是"删缓存"而不是"更新缓存"

假设两个线程同时写同一条数据：

```text
线程A：更新DB为 value=1 → 更新缓存为 value=1
线程B：更新DB为 value=2 → 更新缓存为 value=2

如果执行顺序变成：
  A更新DB=1 → B更新DB=2 → B更新缓存=2 → A更新缓存=1
  结果：DB里是2，缓存里是1，不一致了
```

删缓存就没这个问题——删掉之后，下次读的时候自然会从 DB 加载最新值。

#### 为什么是"先更新 DB，再删缓存"

如果先删缓存再更新 DB：

```text
1. 线程A 删缓存
2. 线程B 来读 → 缓存 miss → 查 DB 读到旧值 → 回填缓存（旧值）
3. 线程A 更新 DB 为新值
结果：DB 是新值，缓存是旧值
```

先更新 DB 再删缓存也有一个极端异常场景：

```text
1. 线程B 读缓存 → miss → 查 DB 读到旧值
2. 线程A 更新 DB → 删缓存
3. 线程B 把旧值回填缓存
结果：缓存还是旧值
```

但这个场景要求"缓存刚好失效 + B读到旧值 + A在B回填之前完成更新和删除"，概率极低。所以 Cache Aside 的"先更新DB再删缓存"在绝大多数场景下是够用的。

### 7.4 延迟双删

延迟双删的思路是在"先删缓存再更新DB"的策略上加一道保险：

```java
redis.del(cacheKey);           // 第1次删缓存
db.update(data);               // 更新数据库
Thread.sleep(500);             // 延迟 500ms
redis.del(cacheKey);           // 第2次删缓存（清理期间被回填的旧值）
```

**为什么它不是银弹：**

1. **延迟时间不好定**——设太短可能清不掉旧回填，设太长又增加不一致窗口。一般建议设为"一次读请求耗时 + buffer"。
2. **第二次删除也可能失败**——网络抖动、Redis 不可用等。工程上通常把第二次删除丢到消息队列里异步重试。
3. **依然不能保证强一致**——只是缩小了不一致窗口。

**更可靠的工程做法：** 监听数据库 binlog（通过 Canal），捕获数据变更事件，异步删除对应的缓存 key。这样只要 DB 变了，缓存一定会被清除：

```text
MySQL → binlog → Canal → MQ → 缓存清理消费者 → redis.del(key)
```

这是目前大厂用得最多的方案，彻底解决了"什么时候删缓存"的问题。

### 7.5 多级缓存架构

对于读多写少的核心场景，单靠 Redis 有时不够（Redis 也有网络开销），可以引入本地缓存：

```text
           ┌─────────┐   ┌─────────┐   ┌──────┐
  请求 ──→ │ Caffeine │──→│  Redis  │──→│ MySQL│
           │ (本地)   │   │ (远程)  │   │      │
           └─────────┘   └─────────┘   └──────┘
              ms级          μs级         10ms级
             容量小         容量大        持久化
```

**本地缓存的失效问题：** 本地缓存分散在各个应用实例的 JVM 里，Redis 里的数据变了，本地缓存怎么知道？常用方案是通过 Redis Pub/Sub 或 MQ 广播失效消息，所有实例收到后清除本地对应 key。

---

## 八、分布式锁、限流与消息场景

### 8.1 Redis 分布式锁最基础正确姿势

最基本的正确实现只需要一条命令：

```text
SET lock_key unique_value NX PX 30000
```

三个要素缺一不可：

- **NX**：key 不存在才设置，保证互斥性。
- **PX 30000**：30 秒过期，防止持锁线程崩溃后锁永远不释放（死锁）。
- **unique_value**：每次加锁用不同的 UUID，解锁时校验是不是自己的锁。

### 8.2 为什么不能直接 DEL 解锁

看这个时序：

```text
线程A 加锁成功（TTL=30s）
  → A 执行业务逻辑，耗时超过 30s，锁自动过期
  → 线程B 加锁成功（拿到了一把新锁）
  → 线程A 业务完成，执行 DEL lock_key
  → 删掉的是 B 的锁！
  → 线程C 也加锁成功了...
  → 并发安全问题
```

正确的解锁方式是用 Lua 脚本保证"比较 value + 删除"是一个原子操作：

```java
// 解锁 Lua 脚本
String UNLOCK_SCRIPT = """
    if redis.call('get', KEYS[1]) == ARGV[1] then
        return redis.call('del', KEYS[1])
    else
        return 0
    end
    """;

public void unlock(String lockKey, String uniqueValue) {
    redisTemplate.execute(
        new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class),
        List.of(lockKey),
        uniqueValue
    );
}
```

### 8.3 锁续期问题（看门狗机制）

基础实现有一个矛盾：TTL 设太短，业务没执行完锁就过期了；TTL 设太长，持锁线程崩溃后要等很久锁才能释放。

Redisson 框架用**看门狗（Watchdog）**机制解决了这个问题：

```text
加锁成功后，启动一个后台定时任务（看门狗）
  → 每隔 TTL/3 的时间检查：持锁线程还活着吗？
  → 如果活着，就把 TTL 重新续到原始值
  → 如果持锁线程已退出（比如崩溃了），看门狗也跟着停
  → 锁在 TTL 到期后自然释放
```

这样业务线程不需要关心 TTL 到底设多少——只要它还在运行，锁就不会过期；一旦它挂了，锁会在一个 TTL 后自动释放。

```java
// Redisson 用法示例
RLock lock = redissonClient.getLock("order:lock:" + orderId);
try {
    // 默认 TTL 30s，看门狗每 10s 续期一次
    if (lock.tryLock(5, 30, TimeUnit.SECONDS)) {
        // 执行业务逻辑
        processOrder(orderId);
    }
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 8.4 可重入锁

基础 `SETNX` 实现的锁不可重入——同一线程再次加锁会失败。Redisson 用 Hash 结构实现了可重入：

```text
key: lock:order:1001
field: thread-uuid   value: 3（重入次数）

加锁时：
  → key 不存在 → HSET + 设 TTL → 成功
  → key 存在且 field 是自己 → HINCRBY +1 → 成功（重入）
  → key 存在且 field 不是自己 → 失败

解锁时：
  → HINCRBY -1
  → 如果计数归零 → DEL key
```

### 8.5 Redis 锁的局限性

Redis 分布式锁是 AP 系统上的工程方案，不是 CP 级别的强一致锁：

1. **主从切换丢锁**：线程 A 在 Master 上加锁成功，锁还没同步到 Slave，Master 就挂了。Slave 被提升为新 Master，线程 B 也能加锁成功。两个线程同时持有锁。
2. **RedLock 算法**：Redis 作者提出的补救方案——在 N 个独立的 Redis 实例上加锁，超过半数成功才算加锁成功。但 Martin Kleppmann（《DERTA》作者）公开质疑了 RedLock 的安全性（依赖时钟假设），业界争议较大。
3. **工程建议**：对于电商下单、库存扣减这类场景，Redis 锁做第一层防护（挡住 99% 的并发），数据库层面的乐观锁或唯一索引做最终兜底。不要把 Redis 锁当成唯一的安全屏障。

### 8.6 限流场景详解

#### 固定窗口

```java
// Lua 脚本：原子性地做 INCR + 设过期
String RATE_LIMIT_SCRIPT = """
    local key = KEYS[1]
    local limit = tonumber(ARGV[1])
    local window = tonumber(ARGV[2])
    local current = redis.call('incr', key)
    if current == 1 then
        redis.call('expire', key, window)
    end
    return current <= limit and 1 or 0
    """;
```

简单好实现，但有**窗口边界突发**问题：如果窗口是 1 分钟限 100 次，用户在 0:59 秒发了 100 次，1:00 秒又发 100 次，实际上 2 秒内承受了 200 次请求。

#### 滑动窗口（用 ZSet 实现）

```java
// score 存请求时间戳，member 存唯一ID
String key = "rate:sliding:" + userId;
long now = System.currentTimeMillis();
long windowStart = now - 60_000; // 1分钟窗口

// Lua 保证原子性
String SLIDING_WINDOW_SCRIPT = """
    local key = KEYS[1]
    local now = tonumber(ARGV[1])
    local windowStart = tonumber(ARGV[2])
    local limit = tonumber(ARGV[3])
    local requestId = ARGV[4]
    
    -- 移除窗口外的记录
    redis.call('zremrangebyscore', key, 0, windowStart)
    -- 统计当前窗口内的请求数
    local count = redis.call('zcard', key)
    if count < limit then
        redis.call('zadd', key, now, requestId)
        redis.call('expire', key, 60)
        return 1
    else
        return 0
    end
    """;
```

滑动窗口没有边界突发问题，但每个请求都要往 ZSet 里写一条记录，内存和性能开销比固定窗口大。

#### 令牌桶 vs 漏桶

- **令牌桶**：固定速率往桶里放令牌，请求来了取令牌，桶空了就限流。允许突发（桶满时可以一次取多个）。Redisson 的 `RRateLimiter` 就是这个思路。
- **漏桶**：固定速率处理请求，多余的排队或丢弃。适合需要严格控制处理速率的场景。

### 8.7 Stream 什么时候值得用

Stream 是 Redis 5.0 引入的消息流结构，支持消费组和消息确认，功能上接近一个轻量 MQ。

**适合用 Stream 的场景：**

1. 项目里没有专业 MQ，但需要简单的异步消费
2. 需要消息持久化（Stream 的数据存在 Redis 内存里）
3. 需要消费组和消息确认（ACK）机制
4. 消息量不大（百万级以下），不需要 Kafka 那种吞吐

**不适合的场景：**

1. 已有 RocketMQ / Kafka / RabbitMQ，消息量很大
2. 需要消息回溯（Stream 支持但性能不如专业 MQ）
3. 需要严格的消息顺序保证

> 面试这样说：Redis Stream 定位是轻量消息流，适合项目内简单异步场景或临时补位。如果已有专业消息中间件，Stream 通常不替代它，但可以作为一些轻量通知、状态同步的辅助通道。

---

## 九、高级用法与工程实践

### 9.1 Lua 脚本

Lua 脚本在 Redis 里以原子方式执行——整个脚本执行期间不会被其他命令插入。这对需要"读+判断+写"组合操作的场景特别有用。

**典型场景：库存扣减（防止超卖）**

```java
String DEDUCT_STOCK_SCRIPT = """
    local stock = tonumber(redis.call('get', KEYS[1]))
    if stock == nil then
        return -1  -- key 不存在
    end
    local quantity = tonumber(ARGV[1])
    if stock >= quantity then
        redis.call('decrby', KEYS[1], quantity)
        return stock - quantity  -- 返回剩余库存
    else
        return -2  -- 库存不足
    end
    """;

Long result = redisTemplate.execute(
    new DefaultRedisScript<>(DEDUCT_STOCK_SCRIPT, Long.class),
    List.of("product:stock:" + productId),
    String.valueOf(buyQuantity)
);
```

**使用注意事项：**

1. **脚本会阻塞主线程**——Redis 是单线程执行命令的，Lua 脚本作为一个整体执行，期间其他客户端的命令都在排队。所以脚本要尽量短小精悍，避免在脚本里做大循环或复杂计算。
2. **脚本里不要用随机和时间函数**——`math.random()`、`redis.call('TIME')` 这些在脚本里每次执行结果可能不同，会导致 AOF 重放和主从复制时结果不一致。Redis 4.6+ 对此做了限制。
3. **EVALSHA 优化**——第一次用 `EVAL` 传完整脚本，Redis 会缓存脚本的 SHA1。后续可以用 `EVALSHA sha1 numkeys ...` 调用，省去脚本传输开销。Spring Data Redis 的 `DefaultRedisScript` 自动处理了这个优化。

### 9.2 Pipeline

Pipeline 解决的是**网络往返次数（RTT）**的问题。不用 Pipeline 时，1000 条命令就是 1000 次网络往返；用 Pipeline 把 1000 条命令打包一次发过去，只需要 1 次往返。

```java
// 批量设置 1000 个 key
List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (int i = 0; i < 1000; i++) {
        connection.set(("batch:key:" + i).getBytes(), ("value:" + i).getBytes());
    }
    return null;  // Pipeline 模式下这里返回 null
});
```

**Pipeline 和事务的区别：**

| 维度 | Pipeline | MULTI/EXEC |
| --- | --- | --- |
| 目的 | 减少网络 RTT | 命令顺序执行 |
| 是否原子 | 不是（中间某条失败，其他照常执行） | 是（命令顺序执行，不被其他客户端命令穿插） |
| 是否回滚 | 无 | 无（Redis 事务不回滚！） |
| 性能提升 | 显著（省网络） | 不明显（甚至有额外开销） |

**实际场景**：初始化缓存（批量预热）、批量删除过期数据、数据迁移脚本里，Pipeline 可以把操作耗时从秒级降到毫秒级。

### 9.3 事务 MULTI/EXEC

Redis 事务和关系型数据库事务差别很大，面试时一定要说清楚：

**Redis 事务保证的是：** 命令按顺序执行，执行过程中不会被其他客户端的命令打断。

**Redis 事务不保证的是：**

1. **没有回滚**——如果事务中第 3 条命令执行失败，前 2 条已经执行的结果不会回滚。这和 MySQL 的事务完全不一样。
2. **没有隔离级别**——虽然命令顺序执行，但 Redis 单线程模型本身就保证了串行，不需要隔离级别这个概念。

```text
MULTI
  SET key1 value1
  SET key2 value2
  INCR key1    -- 如果 key1 不是整数，这里会报错
EXEC
  → key1 和 key2 已经被设置了，不会因为 INCR 报错而回滚
```

**WATCH 实现乐观锁：**

```text
WATCH balance_key        -- 监视余额 key
MULTI
  DECRBY balance_key 100 -- 扣款
  INCR order_count       -- 订单计数+1
EXEC
  → 如果在 WATCH 之后、EXEC 之前，有其他客户端修改了 balance_key
  → EXEC 返回 nil，整个事务被取消
  → 客户端可以重试
```

工程上用得不多，因为 Redisson 的分布式锁已经能覆盖大多数需要"安全更新"的场景。

### 9.4 过期策略与淘汰策略

#### Redis 的过期删除机制

Redis 用的是**惰性删除 + 定期删除**的组合：

- **惰性删除**：访问一个 key 时，检查是否过期，过期就删。不访问就不删。
- **定期删除**：Redis 每 100ms 随机抽查一批设置了 TTL 的 key，过期的就删。如果过期比例超过 25%，继续再抽查一轮。

这意味着：一个 key 过期了，如果你不去访问它、也没被定期抽查到，它可能一直赖在内存里占空间。这就是为什么 `maxmemory` 和淘汰策略很重要——它是内存的最终兜底。

#### 八种淘汰策略

| 策略 | 范围 | 淘汰逻辑 | 适用场景 |
| --- | --- | --- | --- |
| `noeviction` | - | 内存满了直接报错，不淘汰 | 不允许丢数据（如分布式锁） |
| `allkeys-lru` | 所有 key | 淘汰最近最少使用的 | **纯缓存最常用** |
| `allkeys-lfu` | 所有 key | 淘汰最不常用的（Redis 4.0+） | 访问频率差异大的缓存 |
| `allkeys-random` | 所有 key | 随机淘汰 | 几乎不用 |
| `volatile-lru` | 有 TTL 的 key | 淘汰最近最少使用的 | 部分 key 不允许淘汰 |
| `volatile-lfu` | 有 TTL 的 key | 淘汰最不常用的 | 同上 |
| `volatile-random` | 有 TTL 的 key | 随机淘汰 | 几乎不用 |
| `volatile-ttl` | 有 TTL 的 key | 淘汰 TTL 最短的 | 想优先淘汰快过期的 |

**LRU vs LFU：** LRU 看的是"最后一次访问时间"，适合"最近访问的大概率还会访问"的场景。LFU 看的是"访问频率"，适合"高频访问的才值得留"的场景。Redis 的 LFU 实现用的是 Morris Counter（近似计数），不是精确频率，所以内存开销很小。

> 面试这样说：如果 Redis 做纯缓存，我一般用 `allkeys-lru` 或 `allkeys-lfu`，因为所有 key 都可以被淘汰；如果同时存了一些不能丢的数据（比如分布式锁、会话），就用 `volatile-lru`，只对设了 TTL 的 key 做淘汰。但更安全的做法是把不同重要程度的数据放到不同 Redis 实例里，做物理隔离。

### 9.5 BigKey 和 HotKey

#### BigKey 的危害

一个 BigKey（比如一个 Hash 存了 10 万字段、一个 ZSet 存了 100 万成员）会带来：

1. **网络阻塞**：读取一个 10MB 的 BigKey，按百兆带宽算要几十毫秒，期间网络带宽被占满。
2. **CPU 阻塞**：Redis 单线程，序列化/反序列化 BigKey 会消耗大量 CPU 时间，其他命令排队等待。
3. **删除慢**：`DEL` 一个 BigKey 可能要几十毫秒甚至几秒。Redis 4.0+ 提供了 `UNLINK` 命令做异步删除（后台线程回收内存），不阻塞主线程。
4. **内存碎片**：BigKey 删除后留下的内存空洞可能无法被小 key 有效利用。

**怎么发现 BigKey：**

```bash
# 方法一：redis-cli 自带扫描
redis-cli --bigkeys

# 方法二：RDB 分析工具
# 用 redis-rdb-tools 或 rdb-cli 分析 RDB 文件，找出最大的 key

# 方法三：MEMORY USAGE 抽查
redis-cli MEMORY USAGE user:profile:10001
```

**治理手段：**

- **拆分**：一个大 Hash 拆成多个小 Hash（按 ID 哈希取模分桶）
- **压缩**：大 value 先 gzip 压缩再存，读的时候解压
- **UNLINK 代替 DEL**：异步删除，不阻塞主线程
- **SCAN 代替 KEYS**：永远不要在生产环境用 `KEYS *`

#### HotKey 的危害

一个 HotKey（比如爆款商品的缓存 key）被大量请求访问，所有请求都打到持有这个 key 的那一个 Redis 节点上，把这个节点打爆。

**怎么发现 HotKey：**

```bash
# 方法一：redis-cli --hotkeys（需要开启 LFU 淘汰策略）
redis-cli --hotkeys

# 方法二：redis-cli --stat 看请求分布
# 方法三：业务监控——哪个接口 QPS 异常高，对应的 key 可能就是热点
```

**治理手段：**

1. **本地缓存**：在应用层用 Caffeine 缓存热点数据，请求根本不用到 Redis。失效问题通过 Redis Pub/Sub 广播解决。
2. **多 key 分散**：把 `product:detail:1001` 复制成 `product:detail:1001:0`、`product:detail:1001:1`...`product:detail:1001:9`，分散到不同节点，读的时候随机选一个。
3. **热点预热**：大促前把热点数据主动加载到 Redis，避免缓存冷启动。
4. **Cluster 散列**：在 Cluster 模式下，热点 key 自然分布在某个槽上。如果多个热点 key 碰巧在同一槽，可以用 `{hashtag}` 主动打散。

---

## 十、常见线上问题与排查

### 10.1 Redis 阻塞了怎么查

看这些方向：

1. 是否有慢命令
2. 是否有大 key
3. 是否执行了 `KEYS`、`FLUSHALL`、大范围扫描
4. 是否有 AOF rewrite / RDB fork 抖动
5. 是否内存打满触发淘汰

### 10.2 常用排查命令

- `INFO`
- `SLOWLOG GET`
- `LATENCY LATEST`
- `MEMORY USAGE key`
- `MEMORY STATS`
- `SCAN`
- `CLUSTER INFO`
- `CLIENT LIST`

### 10.3 内存暴涨怎么想

先分层：

1. 业务写入真的变多了？
2. TTL 失效配置是否异常？
3. 是否有大 key / 无过期 key？
4. 是否 COW 导致 fork 阶段额外内存膨胀？

### 10.4 缓存命中率低怎么查

重点看：

- key 设计是否离散过头
- TTL 是否太短
- 是否频繁删除
- 是否本来就不是热点数据

---

## 十一、面试高频回答模板

### 11.1 Redis 为什么快

> Redis 快的核心原因不是单一因素，而是内存访问、绝大多数命令复杂度低、核心命令执行路径单线程避免锁竞争、网络层采用 IO 多路复用，以及比较激进的内部数据结构设计共同作用的结果。

### 11.2 Redis 持久化怎么选

> 如果 Redis 只是做纯缓存，可以接受弱持久化；如果承载高价值状态数据，通常会选择 AOF `everysec`，并结合 RDB 或混合持久化来兼顾恢复速度和数据安全。真正要注意的是大实例 `fork` 带来的阻塞和内存膨胀。

### 11.3 缓存一致性怎么保证

> Redis 和 MySQL 组合大多数场景只能做最终一致。工程上常用 Cache Aside，写路径优先更新数据库再删除缓存。如果业务不能接受短暂脏读，就要进一步引入消息、重试、延迟双删或直接减少缓存参与关键写路径。

### 11.4 Redis 分布式锁靠谱吗

> 能用，但要清楚边界。基础正确姿势是 `SET key value NX PX` 加唯一值，解锁用 Lua 校验 value 后删除。它适合做工程级互斥，不适合替代数据库事务锁去承载特别强一致的核心扣减链路。

### 11.5 主从、哨兵、集群区别

> 主从解决读扩展和冗余，哨兵解决故障检测与自动切换，集群解决分片和高可用。哨兵不是分片方案，集群也不能天然解决大 key 和热 key 问题。

---

## 最后建议

Redis 这块，四年经验想在面试里拉开差距，重点不是背命令，而是把下面这条线讲顺：

> Redis 为什么快，缓存为什么会出一致性问题，分布式锁为什么有边界，持久化和高可用怎么选，线上 BigKey/HotKey/慢命令怎么查。

你把这条主线吃透，Redis 基本就不再是"会用"，而是"真正在工程里用过并思考过"。
