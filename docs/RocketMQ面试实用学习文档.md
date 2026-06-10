# RocketMQ 面试实用学习文档

> 适合 3-5 年 Java 工程师面试冲刺。目标不是只会“发消息、收消息”，而是能把 RocketMQ 的架构、消息模型、顺序/事务/重试/积压/幂等讲清楚，并能落到真实业务链路。

![RocketMQ 架构与消息流](images/rocketmq-01-architecture.svg)

## 先看一个直观示例：下单成功后异步发积分

RocketMQ 最直观的作用是：**把主链路和非核心链路解耦**。下单接口只负责创建订单，积分、短信、优惠券等后置动作通过消息异步执行，避免把用户请求卡在多个下游系统上。

订单服务发送消息：

```java
@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final RocketMQTemplate rocketMQTemplate;

    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(CreateOrderRequest request) {
        Order order = Order.create(request);
        orderMapper.insert(order);

        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(),
                order.getUserId(),
                order.getPayAmount()
        );

        rocketMQTemplate.convertAndSend("order-created-topic", event);
        return order.getId();
    }
}
```

积分服务消费消息：

```java
@Component
@RocketMQMessageListener(
        topic = "order-created-topic",
        consumerGroup = "point-service-order-created-group"
)
public class OrderCreatedConsumer implements RocketMQListener<OrderCreatedEvent> {

    private final PointAccountService pointAccountService;
    private final ConsumeLogMapper consumeLogMapper;

    @Override
    public void onMessage(OrderCreatedEvent event) {
        String messageKey = "order-created:" + event.orderId();
        if (consumeLogMapper.exists(messageKey)) {
            return;
        }

        pointAccountService.addPoint(event.userId(), event.payAmount());
        consumeLogMapper.insert(messageKey);
    }
}
```

这个例子体现了 RocketMQ 的几个核心价值：

1. 下单主链路不再同步等待积分系统。
2. 消息失败可以重试，提高最终成功率。
3. 消费端必须做幂等，因为 MQ 通常是至少一次投递。
4. 积分系统慢了只会造成消息积压，不会直接拖慢下单接口。

生产上如果要更稳，可以把“订单本地事务”和“消息发送”改造成事务消息或本地消息表 + 补偿任务。

## 目录

- [一、RocketMQ 面试主线](#一rocketmq-面试主线)
- [二、RocketMQ 到底解决什么问题](#二rocketmq-到底解决什么问题)
- [三、核心架构与角色分工](#三核心架构与角色分工)
- [四、消息发送与存储原理](#四消息发送与存储原理)
- [五、消费模型、负载均衡与重试](#五消费模型负载均衡与重试)
- [六、顺序消息、延时消息、事务消息](#六顺序消息延时消息事务消息)
- [七、幂等、重复消费与最终一致性](#七幂等重复消费与最终一致性)
- [八、高级用法与工程实践](#八高级用法与工程实践)
- [九、常见线上问题与排查](#九常见线上问题与排查)
- [十、面试高频回答模板](#十面试高频回答模板)

---

## 一、RocketMQ 面试主线

面试常见追问链路：

```text
为什么要用 MQ
  -> RocketMQ 架构是什么
  -> 消息怎么存
  -> 顺序消息怎么保证
  -> 消费失败怎么重试
  -> 事务消息怎么实现
  -> 如何避免重复消费
  -> 消息积压、丢失、乱序怎么处理
```

RocketMQ 面试里真正拉开差距的点是：

1. 不只会 API
2. 知道消息系统不是数据库事务
3. 知道“至少一次投递”带来的幂等问题
4. 知道线上积压和消费失败怎么处理

---

## 二、RocketMQ 到底解决什么问题

MQ 在业务里常见价值：

1. **异步解耦**
2. **削峰填谷**
3. **广播通知**
4. **最终一致性链路编排**

典型场景：

| 场景 | 说明 |
| --- | --- |
| 下单后发券、发消息、写积分 | 异步解耦 |
| 秒杀请求入队 | 削峰 |
| 用户信息变更通知多个系统 | 广播/事件分发 |
| 本地事务后驱动下游处理 | 最终一致性 |

面试里最好能强调：

> MQ 不是让系统更简单，而是把同步复杂度换成异步复杂度。好处是解耦和抗峰值，代价是幂等、重试、顺序、积压和一致性问题要自己设计。

---

## 三、核心架构与角色分工

### 3.1 核心角色

| 角色 | 职责 |
| --- | --- |
| Producer | 发送消息 |
| Consumer | 消费消息 |
| Broker | 存储消息、转发消费 |
| NameServer | 路由发现 |

### 3.2 NameServer 不是什么

很多人会把 NameServer 想成“强一致注册中心”，这是不准确的。

它更像：

- 轻量路由服务

特点：

- 结构简单
- 去中心化
- Broker 定期上报路由信息

### 3.3 Topic、Queue、ConsumerGroup

这是 RocketMQ 的基础语义模型。

- Topic：消息主题
- Queue：Topic 下的物理队列分片
- ConsumerGroup：一组逻辑消费者

理解这三个概念后，你才能真正讲清：

- 并发消费
- 顺序消费
- 负载均衡

---

## 四、消息发送与存储原理

### 4.1 发送流程

大体上是：

```text
Producer 获取 Topic 路由
  -> 选择 Broker / Queue
  -> 发送消息
  -> Broker 落盘
  -> 返回发送结果
```

### 4.2 RocketMQ 为什么适合高吞吐

因为它在存储层大量利用了：

- 顺序写
- 零拷贝相关思路
- CommitLog 统一追加存储

### 4.2.1 零拷贝在 RocketMQ 中的应用

RocketMQ 在两个方向上用了零拷贝技术：

**写入方向 — mmap（内存映射）**：
Producer 发消息时，Broker 用 mmap 把 CommitLog 文件映射到内存，写入 mmap 缓冲区 = 写入 PageCache，由 OS 负责刷盘。对应 Java 的 `MappedByteBuffer`。适合写入场景，因为写操作变成内存操作。

**读取方向 — sendfile（文件传输）**：
Consumer 拉消息时，Broker 用 `sendfile` 系统调用直接把文件数据从 PageCache 发送到 Socket 缓冲区，不经过用户空间。对应 Java 的 `FileChannel.transferTo()`。减少了一次内核态 → 用户态 → 内核态的拷贝。

```text
传统读写：
磁盘文件 → 内核缓冲区 → 用户空间 → Socket缓冲区 → 网卡（4次拷贝，4次上下文切换）

sendfile：
磁盘文件 → 内核缓冲区 → Socket缓冲区 → 网卡（2-3次拷贝，2次上下文切换）
```

注意：mmap 适合小数据量写入（CommitLog 每次写入消息通常几 KB），sendfile 适合大数据量读取（Consumer 批量拉取）。两者不可混用。

### 4.3 常见存储文件角色

高层理解上你需要知道：

- CommitLog：消息主存储
- ConsumeQueue：消费逻辑队列索引
- IndexFile：按 key 查消息的辅助索引

### 4.3.1 CommitLog 写入流程详解

消息到达 Broker 后的写入链路：

```text
消息到达 Broker
  → 加写锁（putMessageLock，保证同一时刻只有一个线程写 CommitLog）
  → 追加写入 CommitLog 文件（顺序写，一个文件默认 1GB，写满后创建新文件）
  → 写入 MappedByteBuffer（mmap 映射的 PageCache）
  → 释放写锁
  → 异步刷盘或同步刷盘（取决于配置）
  → 主从模式下：异步复制到 Slave / 同步复制到 Slave
  → 返回发送结果给 Producer
```

**为什么用 mmap（内存映射文件）而不是直接写磁盘？**

mmap 把磁盘文件映射到用户空间的虚拟内存，写入 mmap 缓冲区就等于写入了 PageCache，由操作系统负责刷盘。好处是：
- 减少一次内核态到用户态的数据拷贝
- 写入操作变成内存操作，延迟极低
- 多个 CommitLog 文件可以同时映射，通过文件偏移量快速定位

**PageCache 刷盘策略**：

| 模式 | 配置 | 行为 | 可靠性 | 性能 |
| --- | --- | --- | --- | --- |
| 异步刷盘 | ASYNC_FLUSH | 写入 PageCache 后立即返回成功，后台线程定时刷盘 | 宕机可能丢少量消息 | 高 |
| 同步刷盘 | SYNC_FLUSH | 写入 PageCache 后等待刷盘完成才返回成功 | 不丢消息 | 低 |

工程实践：大多数场景用异步刷盘 + 主从同步复制（SYNC_MASTER），兼顾性能和可靠性。金融场景可能要求同步刷盘。

**ConsumeQueue 是怎么构建的？**

CommitLog 写入后，`ReputMessageService` 后台线程持续从 CommitLog 末尾读取新消息，为每条消息构建 ConsumeQueue 条目：

```text
ConsumeQueue 每条记录 = [CommitLog 偏移量(8字节) | 消息大小(4字节) | Tag HashCode(8字节)]
```

这就是为什么消费端按队列消费很快——只需要顺序读 ConsumeQueue 索引，再按偏移量去 CommitLog 读消息体。

**IndexFile 的作用**：

IndexFile 是一个类似 HashMap 的文件结构（Header + SlotTable + IndexLinked），支持按 MessageKey 或 MessageId 快速查找消息。主要用于运维排查和消息轨迹查询，不参与正常消费链路。

### 4.4 为什么不是直接按队列存消息

因为统一写入 CommitLog 更利于：

- 顺序写磁盘
- 提高吞吐

而消费侧再通过逻辑索引映射到对应队列。

### 4.5 发送可靠性不要回答成“绝对不丢”

更成熟的表达是：

> MQ 的可靠性取决于发送确认、Broker 落盘策略、主从复制策略和消费确认机制的组合。工程上追求的是尽量可靠，而不是脱离配置和部署谈绝对不丢。

---

## 五、消费模型、负载均衡与重试

### 5.1 集群消费和广播消费

| 模式 | 含义 |
| --- | --- |
| 集群消费 | 同一个消费组内一条消息只被一台消费者处理 |
| 广播消费 | 消费组内每台消费者都收到一份 |

### 5.2 负载均衡本质

RocketMQ 在集群消费下会把队列分配给不同消费者实例。  
所以一个核心认知是：

**消费并发度通常先受队列数限制。**

### 5.2.1 消费偏移量（Offset）管理

RocketMQ 的消费进度用 Offset 表示——每个 Queue 消费到了哪个位置。

**Offset 存储位置**：

| 模式 | 存储位置 | 适用场景 |
| --- | --- | --- |
| 远程存储（默认） | Broker 端（consumer_offsets Topic） | 集群消费，多实例共享进度 |
| 本地存储 | Consumer 本地文件 | 广播消费或特殊场景 |

**Offset 提交流程**：

```text
Consumer 消费完一批消息
  → 更新本地 PullRequest 的 offset
  → 定时（默认 5 秒）批量提交到 Broker
  → Broker 写入 consumer_offsets Topic（内部 Topic，16 个 Queue）
  → consumer_offsets 定期 Compact（只保留每个 Group + Queue 最新 offset）
```

**面试高频追问：消费失败时 offset 怎么处理？**

RocketMQ 消费模式是"先消费，后提交 offset"。如果消费失败，当前批次不会提交 offset，而是进入重试队列（%RETRY%Topic）。重试消息消费成功后才更新原 Queue 的 offset。这就是为什么消费失败不影响进度，但也意味着重试期间该 Queue 的 offset 不会前进。

### 5.2.2 Consumer Rebalance 协议

当消费者数量变化或队列数变化时，需要重新分配队列与消费者的映射关系。

**触发条件**：
- 消费者实例上线或下线
- Topic 的读写队列数变更
- 消费者订阅关系变更

**分配策略**：
- 平均分配（默认）：Queue 按顺序均分给 Consumer
- 一致性哈希：尽量保持原有分配不变，减少迁移
- 自定义策略：实现 `AllocateMessageQueueStrategy` 接口

**Rebalance 过程中的问题**：
- 短暂消费暂停（正在分配的 Queue 无人消费）
- 可能出现重复消费（旧 Consumer 还没停，新 Consumer 已经开始）
- 工程上建议：消费端做好幂等，Rebalance 期间不要做不可逆操作

### 5.3 消费失败会怎样

RocketMQ 常见是：

- 失败后重试
- 重试多次仍失败进入死信队列

### 5.4 为什么不能把重试当回滚

因为消息系统天然更偏：

- 至少一次投递

所以重试意味着：

- 业务可能收到重复消息

这就要求消费端幂等。

### 5.5 积压本质上是什么问题

通常不是“MQ 坏了”，而是：

1. 生产速度大于消费速度
2. 下游依赖慢
3. 消费逻辑过重
4. 队列数和消费者并发不匹配

---

## 六、顺序消息、延时消息、事务消息

### 6.1 顺序消息怎么理解

顺序通常分：

- 全局顺序
- 分区顺序

工程上大多数用的是：

- **分区顺序**

也就是同一业务 key 的消息进入同一队列，队列内按顺序消费。

### 6.2 为什么很少追求全局顺序

因为全局顺序意味着：

- 所有消息都受限于一个串行瓶颈

吞吐会很难看。

### 6.3 延时消息

适合：

- 订单超时关闭
- 延迟通知
- 延迟补偿

但要知道：

- 它不是高精度定时器
- 更适合业务级延迟任务

### 6.4 事务消息

它解决的是：

- 本地事务和消息发送之间的一致性问题

高层流程通常是：

1. 先发半消息
2. 执行本地事务
3. 提交或回滚消息
4. Broker 通过回查兜底最终状态

### 6.5 事务消息不是分布式事务银弹

它适合：

- 本地事务成功后需要可靠驱动下游

但依然是：

- 最终一致

不是强一致两阶段提交替代品。

### 6.6 消息过滤机制

RocketMQ 支持在 Broker 端过滤消息，避免无效消息到达 Consumer。

**Tag 过滤**：

Producer 发送时设置 Tag：`msg.setTags("order-created")`
Consumer 订阅时指定 Tag：`consumer.subscribe("topic", "order-created || order-paid")`

过滤流程（高效的两级过滤）：

```text
1. ConsumeQueue 存储了每条消息 Tag 的 HashCode（8字节）
2. Broker 先用 HashCode 快速比对（不需要读消息体）
3. HashCode 匹配后，再从 CommitLog 读完整消息，用 Tag 字符串精确比对（防止哈希碰撞）
4. 通过的消息才返回给 Consumer
```

**SQL92 过滤**：

更灵活但性能较低。Broker 用 SQLite 引擎对用户定义的 SQL 表达式求值：

```java
consumer.subscribe("topic", MessageSelector.bySql("price > 100 AND region = 'shanghai'"));
```

面试中如果被问 Tag 和 SQL92 怎么选：Tag 过滤性能远优于 SQL92，大多数业务场景用 Tag + 合理拆分 Topic 就够了。SQL92 适合需要多属性组合过滤且对吞吐要求不高的场景。

---

## 七、幂等、重复消费与最终一致性

### 7.1 为什么 MQ 场景幂等几乎必聊

因为：

- 生产端可能重试
- Broker 可能重复投递
- 消费端可能处理后 ack 失败

所以重复消费是常态边界，不是异常特例。

### 7.2 幂等常见做法

1. 业务唯一 ID 去重
2. 数据库唯一索引
3. 状态机控制
4. 幂等表
5. Redis 防重

### 7.3 最终一致性怎么讲更稳

不要说“MQ 保证一致性”，更准确是：

> MQ 通过异步事件驱动把跨系统事务从同步强一致改造成最终一致。真正的一致性保障来自本地事务、可靠投递、消费重试和幂等控制的组合。

---

## 八、高级用法与工程实践

### 8.1 消息 key 和 tag

建议业务上合理使用：

- key：便于排查、索引、追踪
- tag：便于同 topic 下分类

### 8.2 主题设计

不要一上来就：

- 一个业务一个 topic
- 或所有业务塞一个 topic

更成熟的维度：

- 业务领域
- 吞吐特征
- 顺序要求
- 权限和隔离需求

### 8.3 消费逻辑不要过重

常见问题：

- 一条消息里做多个远程调用
- 长事务
- 大量锁竞争

这样很容易导致：

- 消费 RT 高
- 重试放大
- 积压

### 8.4 批量消费与批量发送

适合高吞吐场景，但要权衡：

- 延迟
- 单次失败影响面
- 幂等复杂度

### 8.5 顺序消费的常见工程取舍

如果业务只要求“同订单顺序”，不要上升成“全系统顺序”。  
分区顺序通常是更合理的工程答案。

### 8.6 Broker 主从同步与高可用

**主从角色**：

- Master：可读写，接收 Producer 消息
- Slave：只读，从 Master 同步数据，分担消费请求

**同步方式**：

| 模式 | 配置 | 行为 | 适用场景 |
| --- | --- | --- | --- |
| 异步复制 | ASYNC_MASTER | Master 写完立即返回成功，后台异步复制到 Slave | 追求性能，可容忍少量丢失 |
| 同步双写 | SYNC_MASTER | Master 等待 Slave 确认后才返回成功 | 金融级场景，不允许丢消息 |

**注意**：RocketMQ 的主从同步是数据复制，不是故障自动切换。Master 挂了不会自动把 Slave 提升为 Master（这是和 Kafka ISR 的关键区别）。需要搭配 DLedger（基于 Raft 的自动选主方案）或依赖运维手动切换。

**面试追问"RocketMQ 怎么保证消息不丢"的完整回答**：

```text
发送端：Producer 用同步发送 + 重试机制，确保 Broker 确认收到
存储端：Broker 配置 SYNC_FLUSH（同步刷盘）保证落盘不丢
复制端：配置 SYNC_MASTER（同步双写）保证主从都有数据
消费端：Consumer 消费成功后才提交 offset，失败则重试
四端配合才能做到端到端不丢消息。
```

---

## 九、常见线上问题与排查

### 9.1 消息积压怎么查

重点看：

1. 哪个 topic / group 积压
2. 是所有队列积压还是部分队列热点
3. 消费端 RT 是否上升
4. 下游依赖是否慢

### 9.2 消费重复怎么查

想这些方向：

1. 消费逻辑成功但 ack 有问题
2. 消费失败触发重试
3. 生产端本身重复发送

### 9.3 顺序乱了怎么查

1. 业务 key 是否正确路由到同一队列
2. 是否使用了并发消费而非顺序消费
3. 消费失败重试是否改变处理顺序

### 9.4 Broker 压力高怎么查

关注：

- 写入吞吐
- 落盘策略
- 主从复制延迟
- 磁盘 IO

---

## 十、面试高频回答模板

### 10.1 RocketMQ 为什么适合业务消息

> RocketMQ 比较适合业务消息链路，尤其是需要高吞吐、顺序、延时、事务消息和较强工程治理能力的场景。它的优势不只是发收消息，而是围绕存储模型、重试机制和业务消息类型支持比较完整。

### 10.2 RocketMQ 架构怎么讲

> RocketMQ 的核心角色有 Producer、Consumer、Broker 和 NameServer。Producer 负责发消息，Broker 负责存储和转发，Consumer 负责消费，NameServer 提供路由发现。Broker 定期上报路由，Producer 和 Consumer 再按路由信息与 Broker 通信。

### 10.2.1 RocketMQ 存储为什么快

> RocketMQ 所有消息顺序追加写入同一个 CommitLog 文件，把随机写变成顺序写。写入时用 mmap 把文件映射到 PageCache，写操作变成内存操作。Consumer 拉取时用 sendfile 零拷贝直接从 PageCache 发送到网卡。刷盘策略可选异步或同步，大多数场景用异步刷盘加主从同步复制兼顾性能和可靠性。消费端通过 ConsumeQueue 索引快速定位消息，不需要扫描整个 CommitLog。

### 10.3 顺序消息怎么保证

> 工程上通常保证分区顺序而不是全局顺序。做法是把同一业务 key 的消息路由到同一队列，再在消费侧按顺序消费该队列。这样能兼顾顺序性和吞吐，不至于把整个系统压成单线程。

### 10.4 事务消息怎么理解

> 事务消息解决的是本地事务和消息发送之间的最终一致性问题。通常先发半消息，再执行本地事务，成功后提交消息，失败则回滚；如果中间状态不确定，Broker 会发起回查。它适合最终一致，不是强一致分布式事务。

### 10.5 为什么消费端一定要幂等

> 因为 RocketMQ 这类 MQ 工程上通常保证至少一次投递，重复消息是正常边界条件。消费端必须通过业务唯一键、状态机、唯一索引或幂等表等手段保证重复消费不会造成业务副作用。

---

## 最后建议

RocketMQ 这块最值钱的不是背术语，而是把这条线讲顺：

> 为什么要异步、消息怎么存、为什么会重复、顺序怎么做、事务消息解决什么、积压怎么排。

把这条线讲清楚，RocketMQ 基本就够硬了。
