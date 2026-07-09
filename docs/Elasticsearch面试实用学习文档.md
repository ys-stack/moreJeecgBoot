# Elasticsearch 面试实用学习文档

> 适合 3-5 年 Java 工程师面试冲刺，也适合从零学习 ES。目标不是只会写查询 DSL，而是能把倒排索引、分片副本、写入链路、搜索链路、相关性、聚合、深分页、集群治理、集群模式、线上排查讲清楚，同时掌握 ES 8.x 向量搜索（dense_vector / kNN / HNSW）在 RAG 场景中的应用。

![Elasticsearch 写入与搜索链路](images/elasticsearch-01-index-search.svg)

---

## 零、先把 ES JSON、字段和查询看懂

如果你看 ES 文档觉得云里雾里，通常不是因为概念太难，而是 ES 把 **HTTP 接口、索引结构、字段类型、查询条件、排序、聚合** 全塞进 JSON 里了。先记住一句话：

> ES 的 JSON 不是普通配置文件，而是在告诉 ES：我要建什么索引、字段怎么存、数据怎么写、查询怎么查、结果怎么排。

### 0.1 一条 ES 请求怎么看

ES 请求一般由两部分组成：

```text
HTTP 方法 + 路径
JSON 请求体
```

例如：

```jsonc
PUT /product_index
{
  "mappings": {
    "properties": {
      "title": { "type": "text" },
      "status": { "type": "keyword" }
    }
  }
}
```

逐行拆开看：

| 位置 | 含义 |
| --- | --- |
| `PUT /product_index` | 创建一个叫 `product_index` 的索引。可以先粗暴理解成 MySQL 里建一张商品搜索表。 |
| `mappings` | 定义字段结构，类似 MySQL 的 `CREATE TABLE`。 |
| `properties` | 字段列表。里面每一个 key 都是一个字段。 |
| `title` | 商品标题字段。 |
| `{ "type": "text" }` | `title` 要分词，适合全文搜索。 |
| `status` | 商品状态字段。 |
| `{ "type": "keyword" }` | `status` 不分词，适合精确过滤，比如 `ON_SALE`。 |

看 ES JSON 时，不要从大括号开始背。先问四个问题：

1. 这是在 **建索引**、**写数据**，还是 **查数据**？
2. 当前字段是要 **全文搜索**，还是 **精确过滤/排序/聚合**？
3. 当前查询条件要不要参与相关性打分？
4. 返回结果需要哪些字段、怎么排序、要不要统计聚合？

### 0.2 字段类型先记这几种就够了

先不要被 ES 的字段类型吓到，面试和业务里最常用的是下面这些：

| 字段类型 | 你可以理解成 | 适合存什么 | 常用查询 |
| --- | --- | --- | --- |
| `keyword` | 原样字符串，不分词 | ID、状态、分类、品牌、手机号、枚举值 | `term`、`terms`、排序、聚合 |
| `text` | 会分词的文本 | 标题、描述、正文、评论内容 | `match` |
| `integer` / `long` | 整数 | 价格分、库存、次数、年龄 | `term`、`range`、排序、聚合 |
| `date` | 时间 | 创建时间、更新时间、日志时间 | `range`、排序、日期聚合 |
| `boolean` | 布尔值 | 是否删除、是否启用 | `term` |
| `object` | 普通 JSON 对象 | 单个对象或不需要保持数组对象关系的数据 | 普通字段查询 |
| `nested` | 独立嵌套对象数组 | 订单明细、人员数组，并且要保持同一个对象内字段关系 | `nested` 查询 |

最重要的是 `keyword` 和 `text`：

```text
keyword：整块存，适合精确查。
text：拆词存，适合模糊搜、全文搜。
```

例子：

```text
字段值："Java Redis 面试"

keyword 存法："Java Redis 面试" 作为一个整体。
text 存法：拆成 "java"、"redis"、"面试" 这些词。
```

所以：

- `status = ON_SALE` 用 `keyword + term`。
- `title 搜 Java Redis` 用 `text + match`。
- `brand 做聚合统计` 用 `keyword`。
- `price 做区间查询` 用数值类型 + `range`。

### 0.3 查询 JSON 先看这几个积木

ES 查询 DSL 看着复杂，本质是几个积木拼起来：

| DSL | 作用 | 典型写法 | 适合字段 |
| --- | --- | --- | --- |
| `match` | 全文搜索，会先分词 | `{ "match": { "title": "Java Redis" } }` | `text` |
| `term` | 精确匹配，不分词 | `{ "term": { "status": "ON_SALE" } }` | `keyword`、数值、布尔 |
| `terms` | 多个精确值匹配 | `{ "terms": { "brand": ["apple", "huawei"] } }` | `keyword` |
| `range` | 范围查询 | `{ "range": { "price": { "gte": 1000, "lte": 20000 } } }` | 数值、日期 |
| `exists` | 字段存在 | `{ "exists": { "field": "coverUrl" } }` | 任意字段 |
| `bool.must` | 必须满足，并参与打分 | 标题必须匹配关键词 | 全文搜索条件 |
| `bool.filter` | 必须满足，但不打分，可缓存 | 状态、分类、价格过滤 | 精确过滤条件 |
| `bool.should` | 最好满足，可提高分数 | 命中品牌、标签时加分 | 加权召回 |
| `bool.must_not` | 必须不满足 | 排除已删除数据 | 过滤条件 |

先把这张表吃透，再看复杂 JSON 就不会迷路。

### 0.4 一个搜索请求的固定骨架

大多数搜索请求都长这样：

```jsonc
POST /索引名/_search
{
  "from": 0,
  "size": 20,
  "_source": ["返回字段1", "返回字段2"],
  "query": {
    "bool": {
      "must": [],
      "filter": [],
      "should": [],
      "must_not": []
    }
  },
  "sort": [],
  "aggs": {}
}
```

各字段含义：

| 字段 | 作用 | 可以先怎么理解 |
| --- | --- | --- |
| `from` | 从第几条开始取 | 第几页的起点 |
| `size` | 返回多少条 | 每页条数 |
| `_source` | 返回哪些字段 | 类似 SQL 里的 select 列 |
| `query` | 查询条件 | 类似 SQL 的 where，但支持全文检索和打分 |
| `must` | 必须满足，参与打分 | 关键词搜索通常放这里 |
| `filter` | 必须满足，不参与打分 | 状态、分类、价格区间放这里 |
| `sort` | 排序 | 按分数、时间、价格排序 |
| `aggs` | 聚合统计 | 分组统计，比如品牌数量、价格区间 |

可以把它类比成 SQL：

```sql
SELECT id, title, brand, price
FROM product_index
WHERE title MATCH 'Java Redis'
  AND status = 'ON_SALE'
  AND category_id = 'book'
  AND price BETWEEN 1000 AND 20000
ORDER BY score DESC, created_at DESC
LIMIT 0, 20;
```

注意：ES 不是 SQL 数据库，这个类比只是帮你理解结构。

---

## 先看一个直观示例：商品搜索

Elasticsearch 最直观的作用是：**让用户按关键词、分类、价格区间、品牌、排序等条件快速搜索商品**。MySQL 可以做精确查询，但面对全文检索、相关性排序、聚合筛选时会很吃力。

### 第一步：先设计商品字段

假设商品搜索页需要这些能力：

| 页面能力 | 需要的字段 | 字段类型 | 原因 |
| --- | --- | --- | --- |
| 搜标题关键词 | `title` | `text` | 标题要分词，比如搜 `Java Redis` 能命中标题里的词。 |
| 按品牌筛选 | `brand` | `keyword` | 品牌是精确值，还要做聚合统计。 |
| 按分类筛选 | `categoryId` | `keyword` | 分类 ID 是精确值，不需要分词。 |
| 按价格区间筛选 | `price` | `integer` | 金额用分存整数，方便范围查询。 |
| 只查上架商品 | `status` | `keyword` | 状态是枚举值，精确过滤。 |
| 按时间排序 | `createdAt` | `date` | 时间字段支持排序和范围查询。 |

对应的 Mapping：

```jsonc
PUT /product_index
{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "title": {
        "type": "text",
        "analyzer": "ik_max_word",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "brand": { "type": "keyword" },
      "categoryId": { "type": "keyword" },
      "price": { "type": "integer" },
      "status": { "type": "keyword" },
      "createdAt": { "type": "date" }
    }
  }
}
```

这段 JSON 的重点不是背格式，而是理解字段选择：

- `title` 用 `text`：为了全文搜索。
- `title.keyword` 是子字段：同一个标题如果将来要精确匹配、排序或聚合，可以走 `title.keyword`。
- `brand/status/categoryId` 用 `keyword`：为了精确过滤和聚合。
- `price/createdAt` 用数值和日期：为了范围查询和排序。

### 第二步：写入一条商品文档

```json
POST /product_index/_doc/10001
{
  "id": "10001",
  "title": "Java 面试突击手册 Redis Spring Cloud Elasticsearch",
  "brand": "tech-book",
  "categoryId": "book",
  "price": 9900,
  "status": "ON_SALE",
  "createdAt": "2026-05-09T10:00:00"
}
```

可以把这段理解成 MySQL 里插入一行商品搜索视图。真正的商品主数据通常仍然在 MySQL，ES 里存的是为了搜索而设计的冗余数据。

### 第三步：先写最小查询

用户只搜标题里的 `Java Redis`：

```json
POST /product_index/_search
{
  "query": {
    "match": {
      "title": "Java Redis"
    }
  }
}
```

这一步只做一件事：对 `title` 做全文检索。`match` 会把 `Java Redis` 分词，再去倒排索引里找包含这些词的商品。

### 第四步：加精确过滤条件

只查上架商品、图书分类、价格在 10 到 200 元之间：

```jsonc
POST /product_index/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "Java Redis" } }
      ],
      "filter": [
        { "term": { "status": "ON_SALE" } },
        { "term": { "categoryId": "book" } },
        { "range": { "price": { "gte": 1000, "lte": 20000 } } }
      ]
    }
  }
}
```

这里的理解关键：

- `must` 里的 `match`：负责搜索关键词，并影响 `_score` 分数。
- `filter` 里的 `term/range`：只负责过滤，不影响分数，性能更好。
- `gte/lte`：大于等于、小于等于。

### 第五步：加分页、返回字段、排序和聚合

```jsonc
POST /product_index/_search
{
  "from": 0,
  "size": 20,
  "_source": ["id", "title", "brand", "price"],
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "Java Redis" } }
      ],
      "filter": [
        { "term": { "status": "ON_SALE" } },
        { "term": { "categoryId": "book" } },
        { "range": { "price": { "gte": 1000, "lte": 20000 } } }
      ]
    }
  },
  "sort": [
    { "_score": "desc" },
    { "createdAt": "desc" }
  ],
  "aggs": {
    "brand_count": {
      "terms": { "field": "brand", "size": 10 }
    }
  }
}
```

这份完整查询可以按模块读：

| 模块 | 作用 |
| --- | --- |
| `from/size` | 分页。 |
| `_source` | 只返回页面要展示的字段，减少网络传输。 |
| `query.bool.must` | 全文搜索标题，计算相关性分数。 |
| `query.bool.filter` | 精确过滤状态、分类、价格，不计算分数。 |
| `sort` | 先按相关性 `_score` 排，再按创建时间排。 |
| `aggs.brand_count` | 按品牌分组统计数量，用于前端筛选栏。 |

工程上通常是：MySQL 存事实数据，ES 存搜索视图。商品变更后通过 MQ 或 binlog 同步到 ES，接受短暂最终一致。

---

## 目录

- [零、先把 ES JSON、字段和查询看懂](#零先把-es-json字段和查询看懂)
- [先看一个直观示例：商品搜索](#先看一个直观示例商品搜索)
- [一、Elasticsearch 面试主线](#一elasticsearch-面试主线)
- [二、Elasticsearch 到底解决什么问题](#二elasticsearch-到底解决什么问题)
- [三、核心概念：Index、Document、Shard、Replica](#三核心概念indexdocumentshardreplica)
- [四、倒排索引与分词原理](#四倒排索引与分词原理)
- [五、写入链路与近实时搜索](#五写入链路与近实时搜索)
- [六、搜索链路、打分与聚合](#六搜索链路打分与聚合)
- [七、Mapping、Analyzer 与字段设计](#七mappinganalyzer-与字段设计)
- [八、深分页、排序与性能优化](#八深分页排序与性能优化)
- [九、集群模式与分布式架构（重点）](#九集群模式与分布式架构重点)
- [十、高级用法与工程场景](#十高级用法与工程场景)
- [十一、常见线上问题与排查](#十一常见线上问题与排查)
- [十二、面试高频回答模板](#十二面试高频回答模板)
- [十三、ES 8.x 向量搜索与 RAG 场景](#十三es-8x-向量搜索与-rag-场景)

---

## 一、Elasticsearch 面试主线

四年 Java 工程师面试 ES，常见追问链路是：

```text
为什么用 ES
  -> 倒排索引是什么
  -> 写入为什么不是立刻可搜
  -> 分片和副本怎么设计
  -> keyword 和 text 区别
  -> match 和 term 区别
  -> 深分页为什么慢
  -> 聚合为什么吃内存
  -> ES 和 MySQL 数据一致性怎么做
  -> 集群节点角色有哪些
  -> 集群脑裂是什么、怎么防
  -> 集群黄/红、写入慢、查询慢怎么排查
  -> ES 8.x 怎么做向量搜索（dense_vector / kNN）
  -> 为什么 RAG 场景选 ES 而不是专用向量库
  -> HNSW 索引原理是什么
```

面试官不是想听你背 DSL，而是想确认你理解：  
**ES 是搜索引擎，不是关系型数据库替代品。**

---

## 二、Elasticsearch 到底解决什么问题

ES 核心解决的是：

1. 全文检索
2. 多条件复杂搜索
3. 日志检索分析
4. 近实时查询
5. 聚合统计

典型 Java 业务场景：

| 场景 | 为什么适合 ES |
| --- | --- |
| 商品搜索 | 分词、相关性排序、过滤、聚合 |
| 日志检索 | 大量文本检索、时间范围筛选 |
| 用户行为分析 | 聚合统计 |
| 订单后台检索 | 多条件组合查询 |
| 内容搜索 | 高亮、召回、相关性 |

### 2.1 ES 和 MySQL 的关系

初学者容易问：ES 能不能替代 MySQL？答案是**不能**，它们解决不同层面的问题：

| 维度 | MySQL | Elasticsearch |
| --- | --- | --- |
| 定位 | 关系型数据库，承载核心业务数据 | 搜索引擎，承载搜索和分析视图 |
| 事务 | 支持 ACID 事务 | 不支持事务 |
| 更新 | 单行更新极快 | 更新代价高（底层是删除+写入新文档） |
| 全文检索 | `LIKE '%关键词%'` 全表扫描，极慢 | 倒排索引，毫秒级 |
| Join | 原生支持 | 不支持传统 Join |
| 数据一致性 | 强一致 | 近实时，最终一致 |

更成熟的表达是：

> ES 适合搜索和分析，不适合作为强一致事务数据库。工程上通常是 MySQL 承载事实数据，ES 承载搜索视图，两者之间通过同步链路做最终一致。

### 2.2 不适合什么

ES 不适合：

- 强事务
- 高频单行更新
- 复杂 Join
- 替代核心账务库

---

## 三、核心概念：Index、Document、Shard、Replica

理解 ES 的核心概念，可以从 MySQL 来类比，但要注意它们之间有本质区别。

### 3.1 基础概念与类比

| ES 概念 | MySQL 类比 | 说明 |
| --- | --- | --- |
| **Index**（索引） | 一张表 | 一类文档的集合。比如 `product_index` 存放所有商品文档。但 Index 不是一个集中存储的表，而是被拆分成多个 Shard 分布在多个节点上。 |
| **Document**（文档） | 一行记录 | JSON 格式的数据单元。一个 Document 包含若干 Field。 |
| **Field**（字段） | 一列 | 文档的属性，比如 `title`、`price`、`status`。 |
| **Shard**（分片） | 表的水平拆分 | Index 被拆成多个 Shard，每个 Shard 是一个独立的 Lucene 索引。Shard 是 ES 分布式存储和并行查询的基本单位。 |
| **Replica**（副本） | 主从复制 | 每个主分片可以有 0 到多个副本。副本提供高可用和读扩展能力。 |
| **Node**（节点） | 一台数据库服务器 | 一个 ES 进程实例。多个 Node 组成 Cluster。 |
| **Cluster**（集群） | 数据库集群 | 一组共同协作的 Node，管理所有 Index 和 Shard。 |

### 3.2 Shard 的本质：Lucene 索引

这是初学者容易忽略的：**一个 Shard 底层就是一个完整的 Lucene 索引**。

Lucene 是 ES 底层的搜索引擎库。每个 Lucene 索引由多个 **Segment**（段）组成：

```text
Index
  ├── Shard 0 (一个 Lucene 索引)
  │     ├── Segment 1（不可变的数据块，包含倒排索引）
  │     ├── Segment 2
  │     ├── Segment 3
  │     └── ...
  ├── Shard 1 (一个 Lucene 索引)
  │     ├── Segment 1
  │     └── ...
  └── Shard 2 (一个 Lucene 索引)
        └── ...
```

关键理解：

- **Segment 是不可变的**：一旦写入就不再修改。"更新"文档的实质是标记旧文档为删除 + 写入新文档到新 Segment。
- **Segment 会被后台合并（Merge）**：多个小 Segment 合并成大 Segment，同时物理删除被标记删除的文档。
- **搜索需要遍历所有 Segment**：Segment 越多，搜索开销越大，所以 Merge 很重要。

### 3.3 分片为什么重要

分片决定了：

- **数据怎么分布**：不同 Shard 可以分布在不同 Node 上，实现水平扩展。
- **查询怎么并行**：搜索请求会分发到所有相关 Shard，并行执行后汇总结果。
- **单分片大小是否健康**：官方建议单个 Shard 大小在 10GB~50GB 之间。太小浪费资源，太大恢复慢。
- **后续扩展成本**：主分片数一旦创建就不能改（除非 Reindex），所以需要提前规划。

### 3.4 分片不是越多越好

初学者常犯的错误：以为分片越多性能越好。实际上：

**分片过多的问题**：

- 集群元数据膨胀：Master 节点需要管理每个 Shard 的状态，Shard 太多会导致集群状态更新变慢。
- 查询扇出变大：每次搜索都要分发到更多 Shard，协调节点合并压力大。
- JVM heap 压力：每个 Shard 在内存中占用一定的固定开销（约 50MB 左右），1000 个 Shard 就要 50GB heap。
- 文件句柄和 Segment 数量增多：每个 Shard 的每个 Segment 都占用文件句柄。

**分片过少的问题**：

- 单分片过大（超过 50GB）：恢复、重分配时间极长。
- 并行度不足：如果一个 Index 只有 1 个 Shard，所有读写都打到一个 Node。
- 无法利用多节点的并行查询能力。

**经验法则**：

```text
合理分片数 ≈ Index 总数据量 / 单个分片目标大小（30GB 左右）
```

比如一个 Index 预计有 300GB 数据，分片数设 10 比较合理。

### 3.5 副本的价值与代价

副本（Replica）有两个核心作用：

1. **高可用**：主分片所在节点挂了，副本可以被提升为主分片，服务不中断。
2. **提升读吞吐**：搜索请求可以分发到主分片或任意副本，副本越多读能力越强。

但副本也有代价：

- **写入放大**：每次写入主分片，都要同步复制到所有副本。1 个主分片 + 2 个副本 = 每次写入 3 份数据。
- **存储成本翻倍**：1 个副本意味着存储量 ×2。

**经验法则**：

- 生产环境至少 1 个副本（保证高可用）。
- 读多写少且需要高吞吐的场景可以设 2 个副本。
- 日志类数据如果磁盘紧张，可以只设 0 个副本（接受不可用风险）。

### 3.6 副本和主分片不能在同一节点

ES 保证：**同一个 Shard 的主分片和副本不会分配在同一个 Node 上**。这是高可用的基本要求——如果主和副本在同一台机器，机器挂了就全丢了。

所以：如果你有 1 个主分片 + 1 个副本，至少需要 2 个 Node。如果只有 1 个 Node，副本会一直处于 Unassigned 状态，集群状态显示 Yellow。

---

## 四、倒排索引与分词原理

倒排索引是 ES 全文搜索的核心数据结构，理解它才能理解 ES 为什么搜索快、为什么 `text` 和 `keyword` 行为不同。

### 4.1 正排索引 vs 倒排索引

**正排索引**（Forward Index）：通过文档 ID 找到文档内容。

```text
doc1 -> "Java Redis 面试"
doc2 -> "Java Elasticsearch 搜索"
doc3 -> "Redis 持久化机制"
```

MySQL 的索引就是正排索引——通过主键快速定位到一行数据。但如果你要搜索包含 "Redis" 的文档，正排索引只能全表扫描。

**倒排索引**（Inverted Index）：通过词项找到包含它的文档列表。

```text
Java        -> [doc1, doc2]
Redis       -> [doc1, doc3]
面试        -> [doc1]
Elasticsearch -> [doc2]
搜索        -> [doc2]
持久化      -> [doc3]
机制        -> [doc3]
```

全文检索时，ES 先在倒排索引里按关键词找到候选文档 ID 列表（Posting List），再做交集/并集运算、打分、排序。这个过程是毫秒级的，不需要扫描所有文档。

倒排索引的结构大致是：

```text
Term Dictionary（词项字典）
  → 所有去重后的词项，有序存储，支持快速二分查找
  
Posting List（文档列表）
  → 每个词项对应一个列表，记录包含该词项的所有文档 ID
  → 列表里还包含词频（TF）、词位置（Position）、偏移量（Offset）等信息
  → 用于相关性打分和高亮
```

ES 使用了一种叫 **Frame Of Reference（FOR）** 的压缩算法来压缩 Posting List，把文档 ID 列表做增量编码和分块压缩，大幅减少存储空间。同时使用 **Roaring Bitmap** 来处理多个 Posting List 的交集和并集运算。

### 4.2 分词 Analyzer 做什么

Analyzer 的作用是把一段文本拆分成一个个 Token（词项），存入倒排索引。

一个 Analyzer 由三部分组成：

```text
原始文本
  ↓
1. Character Filter（字符过滤器）
   去掉 HTML 标签、替换特殊字符等
  ↓
2. Tokenizer（分词器）
   按规则拆分成 Token。比如按空格拆、按标点拆、中文按词典拆
  ↓
3. Token Filter（词项过滤器）
   转小写、去停用词（a/the/的/了）、同义词扩展等
  ↓
最终 Token 列表 → 写入倒排索引
```

常用 Analyzer：

| Analyzer | 行为 | 适用场景 |
| --- | --- | --- |
| `standard` | 按空格和标点拆词，转小写 | 英文默认选项 |
| `ik_max_word` | 中文分词，尽量细粒度拆分 | 中文搜索（搜索时用） |
| `ik_smart` | 中文分词，粗粒度拆分 | 中文搜索（索引时用） |
| `whitespace` | 只按空格拆 | 特殊场景 |

**ik_max_word vs ik_smart** 的区别：

```text
文本: "中华人民共和国"

ik_max_word（细粒度）: "中华人民共和国", "中华人民", "中华", "华人", "人民共和国", "人民", "共和国", "共和", "国"
ik_smart（粗粒度）: "中华人民共和国"
```

工程上常见做法：索引时用 `ik_max_word`（尽量多拆词，提高召回率），搜索时用 `ik_smart`（粗粒度，提高精确度）：

```json
"title": {
  "type": "text",
  "analyzer": "ik_max_word",
  "search_analyzer": "ik_smart"
}
```

### 4.3 `text` 和 `keyword` 区别

这两个类型的本质区别在于数据怎么存：`keyword` 存原样值，`text` 存分词后的 token。

```text
字段值: "Java Redis 面试突击"

keyword 存储: "Java Redis 面试突击"（一整块，不做任何处理）
text 存储（standard analyzer）: ["java", "redis", "面试", "突击"]（分词后的小写 token）
```

`keyword` 适合精确匹配场景：状态码 `ON_SALE`、分类 ID `book`、用户 ID、标签。你可以把它理解成 MySQL 的 `VARCHAR` 加了索引，等值查询、范围查询、聚合、排序都没问题。

`text` 适合全文搜索场景：用户拿一段自然语言来搜，需要召回语义相关的文档。倒排索引里存的是分词后的 token 到文档的映射，原始文本本身不在倒排里。

| 类型 | 是否分词 | 适用场景 | 能聚合/排序 | 能全文搜索 |
| --- | --- | --- | --- | --- |
| `text` | 是 | 全文搜索 | 不建议（聚合的是 token 不是原文） | 是 |
| `keyword` | 否 | 精确匹配、排序、聚合 | 是 | 只能精确匹配 |

高频坑：

- 对 `text` 做聚合，出来的 key 是分词后的 token 而不是原文。比如 100 篇标题 `"Java 面试 xxx"` 的文章，聚合结果是 `"java"` 出现 100 次，而不是每个标题一个桶
- 对 `keyword` 做全文搜索，ES 会把搜索词和整个字段值做精确比较。搜 `"redis"` 匹配不到 `"Java Redis 面试突击"`，因为整块字符串不相等

工程上常用 `fields` 多字段映射，让同一个字段既能全文搜索又能精确匹配：

```json
"title": {
  "type": "text",
  "analyzer": "ik_max_word",
  "fields": {
    "keyword": { "type": "keyword" }
  }
}
```

这样 `title` 走全文搜索，`title.keyword` 走精确匹配和聚合。

### 4.4 `match` 和 `term` 区别

这两个查询的区别和上面的字段类型是一一对应的：**字段类型决定数据怎么存，查询方式决定数据怎么查**。

`term` 不做任何分析。你写 `{"term": {"status": "ON_SALE"}}`，ES 直接拿 `"ON_SALE"` 去倒排索引里精确查找。它和 `keyword` 字段是天然搭档。

你也可以对 `text` 字段用 `term`，但这时候 ES 是拿你的输入去和分词后的 token 做精确比较，不是和原文比。这里有一个容易踩的坑：

```text
文档: "Java Redis 面试"  →  text 分词后: ["java", "redis", "面试"]

{"term": {"title": "redis"}}  → 命中（token 里有小写 "redis"）
{"term": {"title": "Redis"}}  → 不命中（token 是小写，大写 "Redis" 不等）
```

这种行为很反直觉，所以一般**不对 `text` 字段用 `term`**。

`match` 会先分析你的输入。你写 `{"match": {"title": "Java Redis 实战"}}`，ES 先把 `"Java Redis 实战"` 也过一遍 Analyzer，拆成 `["java", "redis", "实战"]`，然后用这些 token 分别去倒排索引里查，把命中的文档按相关性打分排序。这是全文搜索的标准用法。

`match` 内部其实是多个 `term` 查询的 OR 组合（默认），可以通过 `operator` 参数控制：

```jsonc
// 默认 or：包含 java 或 redis 的文档都返回，按命中数量打分
{"match": {"title": "Java Redis"}}

// and：必须同时包含 java 和 redis 才返回
{"match": {"title": {"query": "Java Redis", "operator": "and"}}}
```

| 查询 | 是否分析输入 | 适合字段类型 | 典型场景 |
| --- | --- | --- | --- |
| `match` | 是 | `text` | 全文搜索、自然语言查询 |
| `term` | 否 | `keyword` | 状态过滤、ID 精确匹配、标签筛选 |

面试表达：

> `match` 面向全文检索，会对查询文本做分词分析再匹配倒排索引；`term` 面向精确值匹配，不做任何分析直接查倒排词项。字段是 `text` 还是 `keyword` 决定了应该用哪种查询，选错了要么召回为零，要么结果不符合预期。

### 4.5 相关性打分：BM25

ES 8.x 默认使用 **BM25** 算法来计算搜索相关性分数（`_score`）。了解打分机制有助于理解为什么某些文档排在前面。

BM25 是 TF-IDF 的改进版，核心思想：

- **TF（Term Frequency，词频）**：一个词在文档中出现越多，得分越高。但 BM25 对 TF 做了饱和处理——出现 10 次和出现 100 次的分数差距不会太大，避免了关键词堆砌。
- **IDF（Inverse Document Frequency，逆文档频率）**：一个词在整个索引中越罕见，它在该文档中出现时权重越高。比如"面试"比"的"更有区分度。
- **文档长度归一化**：同样出现一次"Redis"，一篇 100 词的短文档比一篇 10000 词的长文档得分更高，因为短文档更聚焦。

```text
BM25 分数 ≈ IDF × (TF × (k1 + 1)) / (TF + k1 × (1 - b + b × 文档长度/平均文档长度))

其中：
  k1 控制 TF 饱和速度（默认 1.2）
  b 控制文档长度影响程度（默认 0.75）
```

工程上可以影响打分的手段：

- `boost`：给某个字段加权。比如 `title` 的 boost 比 `description` 高。
- `function_score`：自定义打分函数，加入业务因子（比如销量、时间衰减）。
- `minimum_should_match`：控制至少匹配多少个词才返回。

---

## 五、写入链路与近实时搜索

理解写入链路是理解"为什么 ES 是近实时"的前提。

### 5.1 写入链路完整流程

一次写入从客户端到最终落盘，要经过以下步骤：

```text
客户端发起写入请求
  │
  ▼
① 协调节点接收请求
  │  协调节点是集群中任意一个 Node，负责路由请求
  │
  ▼
② 路由计算：shard = hash(doc_id) % number_of_primary_shards
  │  根据文档 ID 的哈希值和主分片数计算出目标 Shard
  │  这也是为什么主分片数创建后不能改——改了路由就变了
  │
  ▼
③ 请求转发到目标主分片所在的数据节点
  │
  ▼
④ 主分片执行写入
  │  a. 数据写入内存 Buffer（此时不可搜索）
  │  b. 同时写入 Translog（WAL 机制，保证崩溃恢复不丢数据）
  │
  ▼
⑤ 主分片把写入操作转发到所有副本分片
  │  副本分片执行同样的操作：写 Buffer + 写 Translog
  │
  ▼
⑥ 所有副本确认写入成功后，协调节点返回客户端成功响应
  │  如果副本写入失败，主分片会标记该副本为失败并通知 Master 重新分配
```

**重要细节**：写入操作的返回需要所有 in-sync 副本（主 + 所有副本）都确认。这意味着副本越多，写入延迟越高。

### 5.2 为什么 ES 是近实时

这是面试高频题。关键在于 **Refresh** 机制：

```text
写入数据 → 内存 Buffer → [Refresh] → Segment（可搜索）→ [Flush] → 磁盘持久化
```

- **内存 Buffer**：写入后数据在内存中，此时搜索看不到。
- **Refresh**：默认每 1 秒执行一次。把内存 Buffer 中的数据生成一个新的 Segment，放在文件系统缓存（OS Page Cache）中。此时数据**可以被搜索到**了，但还没有刷到磁盘。
- **Flush**：默认 30 分钟或 Translog 达到 512MB 时触发。把 OS Page Cache 中的 Segment 持久化到磁盘，并清空 Translog。

所以 ES 的"近实时"指的是：写入后最多等 1 秒（Refresh 间隔）就能搜索到。这不是严格实时。

如果业务需要写完立刻可搜，可以手动触发 Refresh：

```json
POST /product_index/_refresh
```

但不建议高频调用，因为每次 Refresh 都会生成新 Segment，太频繁会导致 Segment 碎片化。

### 5.3 Translog 的作用

Translog 是 ES 的 Write-Ahead Log（WAL），和 MySQL 的 redo log 类似：

- **目的**：保证已确认写入的数据不丢失。
- **场景**：如果 Node 突然崩溃，内存 Buffer 中还没有 Flush 到磁盘的数据会丢失。重启后 ES 通过重放 Translog 恢复这些数据。
- **生命周期**：Flush 操作会持久化 Segment 并清空 Translog。两次 Flush 之间的 Translog 保留所有写入操作。

Translog 的持久性可以通过 `index.translog.durability` 控制：

| 值 | 行为 | 数据安全性 |
| --- | --- | --- |
| `request`（默认） | 每次写入都同步刷盘 Translog | 最安全，已确认写入不丢 |
| `async` | 异步刷盘（默认每 5 秒） | 高性能但有丢失风险 |

### 5.4 Refresh、Flush、Merge 区别

| 动作 | 触发条件 | 做什么 | 对搜索的影响 |
| --- | --- | --- | --- |
| **Refresh** | 默认每 1 秒 | 内存 Buffer → 新 Segment（放在 OS Page Cache） | 数据变得可搜索 |
| **Flush** | 默认 30 分钟或 Translog 512MB | Segment 持久化到磁盘 + 清空 Translog | 无直接影响 |
| **Merge** | 后台自动进行 | 合并多个小 Segment 为大 Segment，物理删除标记删除的文档 | 减少 Segment 数量，提升搜索性能 |

### 5.5 写入优化常见手段

| 优化方式 | 原理 | 适用场景 |
| --- | --- | --- |
| **Bulk 批量写入** | 把多条写入合并成一次请求，减少网络往返和协调开销 | 所有写入场景都应该用 Bulk |
| **调大 Refresh Interval** | `index.refresh_interval: 30s`，减少 Segment 生成频率 | 大批量导入数据时 |
| **先关副本再开** | 全量导入时设副本为 0，导入完再恢复 | 初始数据导入 |
| **避免频繁更新** | ES 更新是删除+重写，频繁更新导致 Segment 碎片 | 设计好文档结构，减少局部更新 |
| **合理路由** | 用 `routing` 参数把相关数据写到同一个 Shard | 减少跨 Shard 查询 |

---

## 六、搜索链路、打分与聚合

### 6.1 搜索链路：Query Then Fetch

ES 的搜索采用 **两阶段查询（Query Then Fetch）** 模式：

```text
客户端发起搜索请求
  │
  ▼
① 协调节点接收请求
  │
  ▼
② 【Query 阶段】协调节点把查询分发到所有相关分片（主或副本）
  │  每个分片在本地执行查询：
  │    a. 对倒排索引做匹配
  │    b. 计算相关性打分（BM25）
  │    c. 按打分排序，返回 TopN 的文档 ID 和分数（不返回文档内容）
  │
  ▼
③ 协调节点收集所有分片返回的 TopN 结果
  │  合并、全局排序，得到最终的 TopN 文档 ID
  │
  ▼
④ 【Fetch 阶段】协调节点根据最终文档 ID，到对应分片获取完整文档内容
  │
  ▼
⑤ 返回给客户端
```

**为什么分两阶段？**

假设有 5 个分片，查询 `from=0, size=10`：

- Query 阶段：每个分片返回本地 Top10 的文档 ID（5 × 10 = 50 个 ID），数据量很小。
- Fetch 阶段：协调节点从 50 个里选出全局 Top10，只获取这 10 个文档的完整内容。

如果一步到位返回完整文档，每个分片要返回 10 个完整文档（可能很大），5 个分片就是 50 个完整文档，网络开销大得多。

### 6.2 Query 和 Filter 区别

| 类型 | 是否打分 | 是否走 Filter Cache | 适用 |
| --- | --- | --- | --- |
| **Query** | 是，计算相关性 `_score` | 否 | 需要相关性排序的搜索条件 |
| **Filter** | 否，只判断匹配/不匹配 | 是，结果会被缓存 | 精确过滤条件（状态、分类、价格区间） |

工程实践：

- 需要相关性排序的条件放 `must`（Query 上下文）。
- 只需要过滤不需要排序的条件放 `filter`。Filter 不打分、速度快、结果可缓存。
- 上面商品搜索示例中，`title` 搜索放 `must`，`status`、`categoryId`、`price` 过滤放 `filter`，这就是最佳实践。

### 6.2.1 查询 DSL 积木表：先会拼，再谈优化

初学 ES 查询时，不要直接背一整段大 JSON。先把常用查询当成积木：

| 你想表达的业务条件 | 推荐 DSL | 示例 | 放在哪里 |
| --- | --- | --- | --- |
| 标题包含关键词 | `match` | `{ "match": { "title": "Redis 持久化" } }` | `must` |
| 状态等于某个值 | `term` | `{ "term": { "status": "ON_SALE" } }` | `filter` |
| 品牌属于多个值 | `terms` | `{ "terms": { "brand": ["apple", "huawei"] } }` | `filter` |
| 价格区间 | `range` | `{ "range": { "price": { "gte": 1000, "lte": 5000 } } }` | `filter` |
| 创建时间最近 7 天 | `range` | `{ "range": { "createdAt": { "gte": "now-7d" } } }` | `filter` |
| 字段不能为空 | `exists` | `{ "exists": { "field": "coverUrl" } }` | `filter` |
| 排除逻辑删除 | `term` + `must_not` | `{ "term": { "deleted": true } }` | `must_not` |
| 命中某条件加分 | `should` | `{ "match": { "tags": "官方" } }` | `should` |

一个比较标准的业务查询模板：

```jsonc
POST /product_index/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "title": "Redis 持久化" } }
      ],
      "filter": [
        { "term": { "status": "ON_SALE" } },
        { "terms": { "brand": ["tech-book", "official"] } },
        { "range": { "price": { "gte": 1000, "lte": 5000 } } }
      ],
      "must_not": [
        { "term": { "deleted": true } }
      ],
      "should": [
        { "match": { "tags": "经典" } }
      ]
    }
  }
}
```

读这段 JSON 时按顺序拆：

1. `must`：用户搜什么，决定相关性分数。
2. `filter`：业务筛选条件，必须满足但不打分。
3. `must_not`：排除哪些数据。
4. `should`：命中了更好，可以提高排序。

面试里可以这样说：

> 我一般把全文检索条件放在 `must`，让它参与 `_score` 打分；把状态、租户、分类、时间范围这类结构化条件放在 `filter`，因为它们只做过滤，不需要打分，还可以利用缓存。这样 DSL 的语义清楚，性能也更好。

### 6.3 为什么查询会慢

常见原因：

- **查询命中范围太大**：没加过滤条件，扫描大量文档。
- **分片过多导致扇出大**：查询要分发到几十个分片，协调节点合并压力大。
- **深分页**：`from + size` 很大时每个分片要取大量候选。
- **脚本查询（Script Query）**：每条文档都要执行脚本，无法利用索引。
- **高基数字段聚合**：对百万个不同值做聚合，内存压力大。
- **没有利用 Filter Cache**：过滤条件放在 `must` 里而不是 `filter` 里。
- **Wildcard / Regexp 查询**：前缀通配（`*xxx`）无法利用倒排索引，退化为全扫描。

### 6.4 聚合为什么可能吃内存

聚合（Aggregation）需要在每个分片上收集候选数据到内存中的桶（Bucket），再汇总。

比如按 `brand` 字段做 terms 聚合，ES 需要：

1. 在每个分片上遍历所有匹配文档的 `brand` 值。
2. 为每个不同的 `brand` 值创建一个桶，累计文档数。
3. 所有分片的桶汇总到协调节点，全局排序。

如果字段基数很高（比如用户 ID 有 100 万个不同值），就会产生 100 万个桶，每个分片都要占用大量内存。这就是为什么：

- 聚合字段尽量用 `keyword` 类型（不分词）。
- 高基数字段避免做 terms 聚合，考虑用 `composite` 聚合分页。
- 聚合请求加 `filter` 缩小范围。

### 6.5 常用聚合类型

聚合可以先理解成 SQL 里的 `GROUP BY` 和统计函数。常用的就几类。

**1. Terms 聚合：按字段值分桶**

适合统计品牌、状态、分类数量：

```json
{
  "aggs": {
    "brand_count": {
      "terms": { "field": "brand", "size": 10 }
    }
  }
}
```

含义：按 `brand` 分组，取数量最多的 10 个品牌。

**2. Date Histogram：按时间分桶**

适合按天、月统计日志量、订单量：

```json
{
  "aggs": {
    "monthly_sales": {
      "date_histogram": {
        "field": "createdAt",
        "calendar_interval": "month"
      }
    }
  }
}
```

含义：按 `createdAt` 的月份分组。

**3. Range 聚合：按区间分桶**

适合价格区间、年龄区间：

```json
{
  "aggs": {
    "price_ranges": {
      "range": {
        "field": "price",
        "ranges": [
          { "to": 1000 },
          { "from": 1000, "to": 5000 },
          { "from": 5000 }
        ]
      }
    }
  }
}
```

含义：统计 10 元以下、10 到 50 元、50 元以上的商品数量。这里假设 `price` 单位是分。

**4. Metrics 聚合：计算统计值**

适合平均值、最大值、最小值：

```json
{
  "aggs": {
    "avg_price": { "avg": { "field": "price" } },
    "max_price": { "max": { "field": "price" } }
  }
}
```

含义：计算平均价格和最高价格。

**5. 嵌套聚合：桶里再算指标**

```json
{
  "aggs": {
    "brand_count": {
      "terms": { "field": "brand" },
      "aggs": {
        "avg_price": { "avg": { "field": "price" } }
      }
    }
  }
}
```

含义：先按品牌分组，再统计每个品牌的平均价格。

---

## 七、Mapping、Analyzer 与字段设计

### 7.1 Mapping 设计决定后期成本

Mapping 就是定义 Index 的字段结构，类似于 MySQL 的 `CREATE TABLE`。Mapping 设计一旦不合理，后面修复往往需要重建索引（Reindex），代价很大。

常见原则：

1. **精确匹配字段用 `keyword`**：状态码、分类 ID、标签、枚举值。
2. **全文检索字段用 `text`**：标题、描述、正文。
3. **金额用整数分存储**：比如 99.9 元存 9990，避免浮点精度问题。
4. **时间字段使用 `date`**：ES 的 `date` 类型支持范围查询和日期聚合。
5. **控制字段数量**：ES 默认限制单个 Index 最多 1000 个字段。字段太多会导致 Mapping 爆炸，集群状态更新变慢。
6. **不需要搜索/聚合的字段设 `enabled: false` 或 `index: false`**：减少倒排索引体积。

### 7.1.1 字段设计决策表

设计 Mapping 时，可以按这个顺序判断：

| 问题 | 选择 |
| --- | --- |
| 这个字段要被用户按关键词搜索吗？ | 用 `text`，配合 `match`。 |
| 这个字段只是状态、ID、枚举、编码吗？ | 用 `keyword`，配合 `term/terms`。 |
| 这个字段要排序或聚合吗？ | 优先用 `keyword`、数值、日期，不要直接用 `text`。 |
| 金额字段有小数吗？ | 业务里通常转成整数分，用 `integer/long`。 |
| 时间字段要做范围查询或按天/月统计吗？ | 用 `date`。 |
| 一个字段既要全文搜索，又要精确匹配吗？ | 用 `text` + `fields.keyword` 多字段。 |
| JSON 数组对象里字段之间必须保持对应关系吗？ | 用 `nested`，否则普通 `object` 可能误匹配。 |
| 字段只用于展示，不需要搜索、排序、聚合吗？ | 可以考虑 `index: false`，减少索引体积。 |

一个常见商品 Mapping 可以这样读：

```jsonc
{
  "title": {
    "type": "text",
    "analyzer": "ik_max_word",
    "fields": {
      "keyword": { "type": "keyword" }
    }
  },
  "brand": { "type": "keyword" },
  "price": { "type": "integer" },
  "createdAt": { "type": "date" }
}
```

- `title`：主字段用于全文搜索。
- `title.keyword`：子字段用于精确匹配、排序或聚合。
- `brand`：品牌筛选和聚合，不需要分词。
- `price`：价格区间查询。
- `createdAt`：时间范围查询和排序。

### 7.2 常用字段类型一览

| 类型 | 用途 | 说明 |
| --- | --- | --- |
| `keyword` | 精确匹配、聚合、排序 | 不分词，存原值 |
| `text` | 全文搜索 | 分词后存 Token |
| `integer` / `long` | 数值 | 整数类型 |
| `float` / `double` | 浮点数 | 小数类型 |
| `boolean` | 布尔 | true/false |
| `date` | 日期 | 支持多种格式 |
| `object` | 嵌套 JSON 对象 | 扁平化存储（注意 nested 陷阱） |
| `nested` | 嵌套数组对象 | 保持数组内对象的独立性 |
| `ip` | IP 地址 | 支持 IP 范围查询 |
| `geo_point` | 地理位置 | 支持距离查询 |
| `dense_vector` | 向量 | ES 8.x 向量搜索 |

### 7.3 Object vs Nested 的陷阱

这是初学者容易踩的坑。ES 存储 JSON 文档时，默认会把嵌套对象**扁平化**：

```jsonc
// 写入的文档
{
  "user": [
    { "name": "张三", "age": 30 },
    { "name": "李四", "age": 20 }
  ]
}

// 默认 Object 类型实际存储为（扁平化）
{
  "user.name": ["张三", "李四"],
  "user.age": [30, 20]
}
```

扁平化后，"张三" 和 20 之间的关联丢失了。如果你查 `user.name = "张三" AND user.age = 20`，会错误地命中，因为 "张三" 和 20 都在扁平化后的数组里。

**解决方案**：用 `nested` 类型，ES 会把数组中的每个对象作为独立的隐藏文档存储，保持字段间的关联：

```json
"user": {
  "type": "nested",
  "properties": {
    "name": { "type": "keyword" },
    "age": { "type": "integer" }
  }
}
```

查询时也必须用 `nested` 查询：

```json
{
  "query": {
    "nested": {
      "path": "user",
      "query": {
        "bool": {
          "must": [
            { "term": { "user.name": "张三" } },
            { "range": { "user.age": { "gte": 25 } } }
          ]
        }
      }
    }
  }
}
```

代价是：nested 查询比 object 慢得多（每个嵌套对象都是独立文档），能用 object 就别用 nested。

### 7.4 Dynamic Mapping 的风险

ES 默认开启 Dynamic Mapping——写入一个新字段时自动推断类型。这在开发阶段方便，但在生产环境很危险：

- **类型推断不准**：比如一个数字字段第一次写入值是 `"123"`（字符串），ES 会推断成 `text`。
- **字段无限膨胀**：如果文档结构不可控（比如用户自定义字段），Mapping 会越来越大。
- **后续查询异常**：类型推断错误导致查询结果不符合预期。

生产建议：

```jsonc
// 关闭自动推断，未定义字段写入会报错
{
  "mappings": {
    "dynamic": "strict",
    "properties": { ... }
  }
}

// 或者忽略未定义字段，不存入索引
{
  "mappings": {
    "dynamic": "false",
    "properties": { ... }
  }
}
```

### 7.5 中文分词

中文搜索一般需要中文分词器，否则 ES 默认按空格和标点拆词，中文整句话会变成一个 Token，搜索召回极差。

要关注：

- **分词粒度**：`ik_max_word`（细粒度）vs `ik_smart`（粗粒度）。
- **同义词**：比如 "电脑" 和 "计算机" 应该匹配。通过 Synonym Token Filter 配置。
- **停用词**：过滤 "的"、"了"、"在" 等高频无意义词。
- **业务词典**：添加领域专有词汇，比如 "微服务"、"分库分表"。IK 分词器支持自定义词典文件。

---

## 八、深分页、排序与性能优化

### 8.1 深分页为什么慢

`from + size` 是最直观的翻页方式，但在 ES 里翻页越深越慢。

原因和搜索的两阶段机制有关：

```text
查询 from = 100000, size = 20

5 个分片的情况：
  每个分片需要取本地 Top 100020 条（from + size）
  → 5 个分片总共返回 500100 个文档 ID 和分数
  → 协调节点对 500100 个结果做全局排序
  → 取第 100001 ~ 100020 条
  → 再去对应分片获取完整文档内容
```

问题很明显：页数越深，每个分片取的候选越多，协调节点合并的数据量越大。

ES 默认限制 `from + size` 最大为 10000（`max_result_window`），超过会直接报错。

### 8.2 深分页解决方式

| 方案 | 原理 | 场景 |
| --- | --- | --- |
| **`search_after`** | 基于上一页最后一条文档的排序值作为游标，只取下一页 | 用户实时翻页（推荐） |
| **Scroll** | 创建一个快照上下文，保持搜索状态 | 大批量数据导出，不适合实时查询，会占用大量资源 |
| **`search_after` + PIT** | Point In Time 快照 + 游标翻页 | ES 7.10+ 推荐方式，一致性更好 |

`search_after` 示例：

```jsonc
// 第一页
POST /product_index/_search
{
  "size": 20,
  "sort": [
    { "createdAt": "desc" },
    { "_id": "asc" }
  ]
}

// 第二页：用第一页最后一条的 sort 值
POST /product_index/_search
{
  "size": 20,
  "search_after": ["2026-05-09T10:00:00", "10001"],
  "sort": [
    { "createdAt": "desc" },
    { "_id": "asc" }
  ]
}
```

`search_after` 的限制：只能向后翻，不能跳页。但对于大多数搜索产品来说，用户实际就是一页一页往下翻。

### 8.3 排序字段注意点

排序字段最好：

- 是 `keyword` 或数值类型（doc_values 友好）。
- 基数合理（不要对 boolean 排序——只有两个值，没有区分度）。
- 不要对 `text` 字段直接排序（排序的是 token 不是原文）。

### 8.4 常见查询优化手段

1. **减少返回字段**：用 `_source` 指定需要的字段，不要返回整个文档。
2. **Filter 替代 Query 过滤**：Filter 不打分、有缓存。
3. **使用 Routing 降低查询分片数**：`GET /index/_search?routing=user_123`，直接路由到特定 Shard。
4. **控制索引和分片规模**：分片数合理、Index 大小合理。
5. **避免高基数大聚合**：用 `composite` 聚合分页。
6. **利用 Filter Cache**：重复使用的 Filter 条件结果会被自动缓存。

---

## 九、集群模式与分布式架构（重点）

这是 ES 区别于 MySQL 的核心知识。ES 天然是分布式的，理解集群模式是理解 ES 高可用、高性能的基础。

### 9.1 集群（Cluster）是什么

一个 ES 集群是一组拥有相同 `cluster.name` 配置的 Node，它们共同协作来存储数据和处理搜索请求。

```text
Cluster: "my-es-cluster"
  ├── Node 1 (192.168.1.10)
  ├── Node 2 (192.168.1.11)
  ├── Node 3 (192.168.1.12)
  └── Node 4 (192.168.1.13)
```

所有 Node 共享同一个集群状态（Cluster State），包含：

- 有哪些 Index
- 每个 Index 的 Mapping 和 Settings
- 每个 Shard 分布在哪个 Node 上
- 每个 Node 的健康状态

集群状态由 Master 节点维护，并同步给所有 Node。

### 9.2 节点角色（Node Roles）

ES 的每个 Node 可以承担不同的角色，合理配置角色是集群规划的关键：

| 角色 | 配置 | 职责 | 资源需求 |
| --- | --- | --- | --- |
| **Master-eligible** | `node.roles: [master]` | 参与主节点选举，负责集群元数据管理（创建/删除 Index、Shard 分配等） | 低 CPU/内存，但需要稳定的磁盘和网络 |
| **Data** | `node.roles: [data]` | 存储数据，执行写入、搜索、聚合 | 高 CPU、大内存、快磁盘 |
| **Ingest** | `node.roles: [ingest]` | 在写入前对文档做预处理（Pipeline） | CPU 密集 |
| **Coordinating only** | `node.roles: []` | 只做请求路由和结果汇总，不存数据 | 中等内存，高网络带宽 |
| **ML** | `node.roles: [ml]` | 运行机器学习任务 | 大内存 |

#### 专用 Master 节点

在大规模集群中，建议把 Master 角色从 Data 节点分离出来，用 3 台专用 Master 节点：

```text
专用 Master 节点（3 台）
  - 不参与数据存储和搜索
  - 只负责集群状态管理
  - 避免因为 Data 节点负载高导致集群状态更新延迟

Data 节点（N 台）
  - 只负责数据存储和查询
  - 不承担集群管理职责
```

为什么是 3 台？和脑裂防护有关，后面会详细讲。

#### 协调节点

**每个 Node 默认就是协调节点**。当收到客户端请求时，任何 Node 都可以作为协调节点来路由请求。专门的 Coordinating-only 节点适用于大集群，减轻 Data 节点的请求处理压力。

### 9.3 集群发现与节点通信

ES 节点之间通过 **Zen Discovery**（7.x 之前）或 **新的集群协调模块**（7.x+）来发现彼此并组成集群。

#### 节点发现

新节点启动时，通过以下方式找到集群：

```yaml
# elasticsearch.yml
cluster.name: my-es-cluster
node.name: node-1

# 种子节点列表——新节点通过联系这些节点来加入集群
discovery.seed_hosts:
  - 192.168.1.10:9300
  - 192.168.1.11:9300
  - 192.168.1.12:9300

# 初始 Master 候选节点列表——首次启动集群时需要
cluster.initial_master_nodes:
  - node-1
  - node-2
  - node-3
```

#### 两种通信端口

ES 使用两个端口：

| 端口 | 默认值 | 用途 |
| --- | --- | --- |
| **HTTP 端口** | 9200 | 客户端（REST API）通信 |
| **Transport 端口** | 9300 | 节点间内部通信（集群状态同步、分片复制等） |

### 9.4 主节点选举（7.x+ 新机制）

ES 7.x 对选主机制做了重大改革，引入了基于 **Raft-like 共识算法** 的新协调模块。

#### 选举流程

```text
① 集群启动或当前 Master 失效
② 所有 Master-eligible 节点发起选举
③ 需要获得「法定多数」票才能当选 Master
④ 当选者成为新 Master，开始管理集群状态
```

**法定多数（Quorum）**：

```text
法定票数 = (Master-eligible 节点数 / 2) + 1

3 个 Master 候选节点 → 需要 2 票
5 个 Master 候选节点 → 需要 3 票
```

这就是为什么**Master-eligible 节点数建议设为奇数**（3 或 5），偶数容易出现平票。

### 9.5 脑裂（Split-Brain）问题

脑裂是分布式系统的经典问题，ES 也不例外。

#### 什么是脑裂

当集群出现网络分区时，一部分节点和另一部分节点失联，两边各自选出了自己的 Master，形成两个独立的"集群"，各自接受写入：

```text
正常状态：
  [Node1(Master)] ←→ [Node2] ←→ [Node3]

网络分区后：
  [Node1(Master)] ←→ [Node2]     [Node3(自选为Master)]
        集群 A                        集群 B
        
两个集群都接受写入，数据不一致！
网络恢复后，两个集群合并，数据冲突。
```

#### 7.x+ 如何防止脑裂

ES 7.x 通过 **法定多数（Quorum）** 机制自动防护：

- 3 个 Master 候选节点：需要 2 票才能选 Master。
- 网络分区后，少数派（1 个节点）凑不够 2 票，选不出 Master，拒绝服务。
- 多数派（2 个节点）可以选出 Master，继续正常工作。

所以 3 个 Master 候选节点最多能容忍 1 个节点故障。5 个 Master 候选节点最多能容忍 2 个。

#### 7.x 之前的配置

ES 6.x 及之前需要手动配置 `discovery.zen.minimum_master_nodes`：

```yaml
# 公式：(Master 候选节点数 / 2) + 1
discovery.zen.minimum_master_nodes: 2
```

如果配错了（比如配成 1），脑裂就防不住。这也是 7.x 引入自动 Quorum 的原因。

### 9.6 集群健康状态

ES 集群有三种健康状态：

| 状态 | 颜色 | 含义 | 影响 |
| --- | --- | --- | --- |
| **Green** | 绿色 | 所有主分片和副本分片都正常分配 | 完全健康 |
| **Yellow** | 黄色 | 所有主分片正常，但有副本分片未分配 | 数据完整，但高可用降级。某节点挂掉可能丢数据 |
| **Red** | 红色 | 有主分片未分配 | 部分数据不可用，相关 Index 的读写会失败 |

#### 常见导致 Yellow 的原因

- 副本数大于可用数据节点数（比如只有 1 个 Node，但设了 1 个副本）。
- 磁盘使用率超过 85%（`cluster.routing.allocation.disk.watermark.low`），ES 停止往该节点分配新分片。
- 节点重启后副本正在恢复中。

#### 常见导致 Red 的原因

- 节点宕机，其上的主分片没有可用的副本可以提升。
- 磁盘使用率超过 95%（`cluster.routing.allocation.disk.watermark.flood_stage`），ES 将 Index 设为只读。
- 分片数据损坏，无法恢复。

#### 排查命令

```bash
# 查看集群整体健康
GET /_cluster/health

# 查看哪些分片未分配
GET /_cat/shards?v&h=index,shard,prirep,state,unassigned.reason&s=state

# 查看未分配原因（非常实用）
GET /_cluster/allocation/explain
{
  "index": "product_index",
  "shard": 0,
  "primary": false
}
```

### 9.7 分片分配与再平衡

#### 初始分配

创建 Index 时，Master 节点根据以下规则决定每个 Shard 放在哪个 Node：

- 主分片和副本不在同一 Node。
- 同一 Index 的 Shard 尽量分散在不同 Node（利用 `cluster.routing.allocation.awareness` 可以做机架/可用区感知）。
- 考虑各 Node 的磁盘剩余空间和负载。

#### 再平衡（Rebalance）

当集群拓扑变化时（加入新 Node、Node 挂掉），Master 会触发分片再平衡，把 Shard 从过载的 Node 迁移到空闲 Node：

```text
原来 3 个 Node，每个 Node 4 个 Shard
  → 加入第 4 个 Node
  → Master 从每个 Node 迁移 1 个 Shard 到新 Node
  → 每个 Node 3 个 Shard，负载均衡
```

#### Shard 迁移的代价

Shard 迁移涉及大量数据在网络上传输，会影响集群性能。可以通过以下配置控制：

```jsonc
// 限制同时迁移的 Shard 数量
PUT /_cluster/settings
{
  "persistent": {
    "cluster.routing.allocation.node_concurrent_recoveries": 2,
    "cluster.routing.allocation.node_initial_primaries_recoveries": 4
  }
}
```

### 9.8 集群规划实战建议

#### 小规模（数据量 < 100GB）

```text
3 个 Node，每个 Node 同时承担 Master + Data + Ingest 角色
每个 Index 1~3 个主分片，1 个副本
```

#### 中规模（100GB ~ 1TB）

```text
3 个专用 Master 节点（低配）
N 个 Data 节点（高配，SSD）
每个 Index 按数据量计算分片数（目标 30GB/分片）
```

#### 大规模（> 1TB）

```text
3 个专用 Master 节点
大量 Data 节点，按热温冷分层
Ingest 节点独立
Coordinating 节点独立
使用 ILM（Index Lifecycle Management）管理数据生命周期
```

#### 热温冷架构

日志类数据最经典的架构：

| 层级 | 节点配置 | 数据特征 | 示例 |
| --- | --- | --- | --- |
| **Hot（热）** | 高配 SSD，Data 角色 | 最近 7 天数据，频繁读写 | 今天的应用日志 |
| **Warm（温）** | 中配 HDD，Data 角色 | 7~30 天数据，偶尔查询 | 上周的日志 |
| **Cold（冷）** | 低配大容量，Data 角色 | 30~90 天数据，很少查询 | 上月的日志归档 |
| **Frozen（冻结）** | 极低配或对象存储 | 90 天以上，基本不查 | 历史归档 |

ES 的 ILM 可以自动把 Index 从热层迁移到温层再到冷层，最后删除。

### 9.9 集群常用管理 API

```bash
# 集群健康
GET /_cluster/health

# 集群设置
GET /_cluster/settings

# 节点信息
GET /_cat/nodes?v&h=name,ip,role,heap.percent,cpu,load_1m

# 分片分布
GET /_cat/shards?v&h=index,shard,prirep,state,docs,store,node

# Index 列表
GET /_cat/indices?v&h=index,health,status,docs.count,store.size

# 集群状态（包含所有元数据，输出很长）
GET /_cluster/state

# 节点热点线程（排查慢查询）
GET /_nodes/hot_threads

# 任务管理（查看正在执行的操作）
GET /_tasks
```

---

## 十、高级用法与工程场景

### 10.1 MySQL 同步 ES 最佳实践（Canal + MQ + Java 消费）

在工程实践中，为了兼顾 MySQL 的事务强一致性与 ES 的高效搜索性能，业内最常用的同步方案是 **基于 CDC (Change Data Capture) 的 Binlog 订阅 + 消息队列异步写入**。该方案能够实现零侵入、高可用与强一致性容错。

#### 10.1.1 架构设计

```text
MySQL (业务写库)
  │ (写事务提交，记录 Binlog)
  ▼
Canal / Debezium (伪装成 MySQL Slave 订阅)
  │ (解析 Binlog 得到行级变更)
  ▼
消息队列 (RocketMQ / Kafka)
  │ (按表主键哈希路由消息，保证顺序性)
  ▼
Java 消费服务 (Spring Boot Consumer)
  │ (批量聚合 Bulk 写入，捕获失败重试)
  ▼
Elasticsearch 集群 (读视图/搜索引擎)
```

#### 10.1.2 工作原理与核心要点

1. **CDC 监听与 Binlog 解析**：
   Canal 伪装成 MySQL 的 Slave 节点，向 MySQL Master 发送 `dump` 协议请求。MySQL Master 收到请求后，将增量 Binlog（必须设置为 `ROW` 格式）推送给 Canal。Canal 解析出发生变化的数据行，识别出变更类型（`INSERT` / `UPDATE` / `DELETE`）及字段值。
2. **消息队列（MQ）削峰与解耦**：
   Canal 将解析出的数据格式化为 JSON 消息发送到消息队列（如 RocketMQ 或 Kafka）。
   * **顺序保障**：为了防止更新乱序（例如同一条数据的 UPDATE 消息比 INSERT 消息先消费），必须根据数据的主键 ID（如 `productId`）进行 Partition 哈希路由，确保同一主键的数据变更全部发送到同一个 MQ 分区/队列中，由单个消费者线程按顺序消费。
3. **消费者批量写入（Bulk API）与容错**：
   Spring Boot Consumer 订阅 MQ。为了避免高频单行写入 ES 造成磁盘 I/O 满载，消费者在内存中进行微批聚合（每 500 毫秒或每 1000 条数据聚合为一批），随后调用 ES `Bulk API` 进行批量写入。
   * **重试与死信队列（DLQ）**：如果 ES 由于负载过高返回 `429 Too Many Requests` 或网络发生瞬时抖动，消费者捕获异常并执行**指数退避重试**。如果重试 5 次依然失败，为了不阻塞后续消息消费，该消息将被投递至**死信队列（DLQ）**并触发邮件告警，由人工或定时脚本重新读取进行补偿更新。
4. **定期数据对账（最终一致性兜底）**：
   虽然 CDC + MQ 保证了最终一致性，但在主备切换、MQ 重试丢消息等极端边缘场景下，仍有丢数据的风险。
   * **对账设计**：企业中通常会部署一个深夜运行的定时任务（如 XXL-JOB 触发），抽取 MySQL 过去一天的增量更新主键，与 ES 进行双向对账。如果发现 ES 缺失文档或版本号滞后，则直接从 MySQL 读取最新主数据覆盖更新 ES。

#### 10.1.3 方案优点
* **零侵入**：业务层只负责对 MySQL 进行增删改，不需要编写任何同步 ES 的代码。
* **高可用与解耦**：当 ES 发生瞬时挂机或熔断时，消息会积压在 MQ 中，不影响主库的正常写入；ES 恢复后，Consumer 会自动消费追平数据。

### 10.2 索引别名与零停机重建

当 Mapping 需要修改时（比如加字段、改分词器），ES 不支持直接改已有字段的类型，必须重建 Index。别名（Alias）可以实现零停机切换：

```text
① 创建新索引 product_index_v2，设好新 Mapping
② 全量从 MySQL 导入数据到 v2
③ 开启增量同步，追平 v1 和 v2 之间的数据差异
④ 原子切换别名：
   POST /_aliases
   {
     "actions": [
       { "remove": { "index": "product_index_v1", "alias": "product_index" } },
       { "add":    { "index": "product_index_v2", "alias": "product_index" } }
     ]
   }
⑤ 验证 v2 正常后，下线 v1
```

应用代码里始终通过别名 `product_index` 读写，切换瞬间完成，对业务无感知。

### 10.3 索引模板（Index Template）

日志类场景经常按时间创建 Index（如 `app-log-2026.06.17`），每次手动建 Mapping 不现实。Index Template 可以自动给新 Index 应用预设的 Mapping 和 Settings：

```json
PUT /_index_template/app-log-template
{
  "index_patterns": ["app-log-*"],
  "template": {
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1,
      "refresh_interval": "5s"
    },
    "mappings": {
      "properties": {
        "timestamp": { "type": "date" },
        "level": { "type": "keyword" },
        "message": { "type": "text", "analyzer": "ik_max_word" },
        "service": { "type": "keyword" }
      }
    }
  }
}
```

之后每次创建匹配 `app-log-*` 的 Index，ES 会自动应用这个模板。

### 10.4 搜索相关性调优

常见手段：

- **字段权重（boost）**：让 `title` 的匹配比 `description` 权重更高。
  ```json
  { "match": { "title": { "query": "Redis", "boost": 3 } } }
  ```
- **function_score**：自定义打分函数，加入业务因子。
  ```json
  // 销量越高、发布时间越近的文档排越前
  {
    "function_score": {
      "query": { "match": { "title": "Redis" } },
      "functions": [
        { "field_value_factor": { "field": "sales", "factor": 0.001 } },
        { "gauss": { "createdAt": { "origin": "now", "scale": "7d" } } }
      ],
      "score_mode": "multiply"
    }
  }
  ```
- **同义词**：通过 Synonym Token Filter 扩展搜索词。
- **业务排序因子**：在 `_score` 基础上叠加业务维度（销量、好评率、时效性）。

### 10.5 Pipeline 数据预处理

ES 的 Ingest Pipeline 可以在写入前对文档做转换：

```jsonc
PUT /_ingest/pipeline/product-pipeline
{
  "processors": [
    { "set": { "field": "createdAt", "value": "{{_ingest.timestamp}}" } },
    { "lowercase": { "field": "brand" } },
    { "remove": { "field": "internalNotes", "ignore_missing": true } }
  ]
}

// 写入时指定 Pipeline
POST /product_index/_doc?pipeline=product-pipeline
{
  "title": "Java 面试手册",
  "brand": "TECH-BOOK"
}
```

Pipeline 由 Ingest 节点执行，不占用 Data 节点资源。

---

## 十一、常见线上问题与排查（真实生产事故演练与处理步骤）

本章以真实的生产事故场景为主线，一步步讲解诊断命令和具体的解决方案。

### 11.1 事故一：集群状态突然变红（Red）或变黄（Yellow）

#### 11.1.1 场景模拟
线上监控告警：ES 集群状态由 Green 变为 Red，业务系统开始出现大面积读取超时或报错。

#### 11.1.2 排查与解决步骤

* **第一步：获取当前集群健康度概览**
  运行以下命令查看哪些指标异常：
  ```bash
  GET /_cluster/health?pretty
  ```
  **诊断要点**：
  * 若 `status` 为 `yellow`，说明所有主分片（Primary Shards）正常，但有部分副本分片（Replica Shards）未分配。
  * 若 `status` 为 `red`，说明至少有一个主分片未分配，数据面临丢失风险。

* **第二步：定位受影响的索引**
  查看具体是哪一个索引出了问题：
  ```bash
  GET /_cat/indices?v&s=health:desc
  ```
  找到状态为 `red` 或 `yellow` 的索引名称（例如 `knowledge_chunks_v1`）。

* **第三步：查找未分配的故障分片**
  定位是该索引的哪一个分片（Shard）没有正常启动：
  ```bash
  GET /_cat/shards?v&h=index,shard,prirep,state,node,unassigned.reason&s=state:asc
  ```
  **典型输出**：
  `knowledge_chunks_v1 | 2 | p | UNASSIGNED | | NODE_LEFT`
  （说明 2 号主分片由于某个节点离线，处于未分配状态）。

* **第四步：诊断未分配的底层根本原因**
  这是 ES 诊断的“终极武器”命令：
  ```bash
  GET /_cluster/allocation/explain
  {
    "index": "knowledge_chunks_v1",
    "shard": 2,
    "primary": true
  }
  ```
  **ES 会在返回的 `explanation` 中直接给出无法分配的原因**：
  * **原因 A：`node_left`（节点宕机）**
    * *解决*：去对应 IP 的物理机查看 ES 进程或 Docker 容器是否因为 OOM 被杀，重新启动该节点。
  * **原因 B：`the shard cannot be allocated because the disk watermark was exceeded`（磁盘空间超出高水位线）**
    * *解决*：清理该节点磁盘，或者通过以下 API 临时调大水位线：
      ```json
      PUT /_cluster/settings
      {
        "persistent": {
          "cluster.routing.allocation.disk.watermark.low": "85%",
          "cluster.routing.allocation.disk.watermark.high": "90%",
          "cluster.routing.allocation.disk.watermark.flood_stage": "95%"
        }
      }
      ```
  * **原因 C：`the replica cannot be allocated on the same node as the primary`（节点太少，副本分片无处容身）**
    * *解决*：如果集群只有一个 Node，副本数必须设为 0；如果是多节点，确保副本分片被路由到其他物理机。

* **第五步：强制手动触发分片重分配**
  在节点重启或清理完磁盘后，如果分片仍未自动恢复，执行：
  ```bash
  POST /_cluster/reroute?retry_failed=true
  ```

---

### 11.2 事故二：写入响应变慢，写入被频繁拒绝（Reject，HTTP 429）

#### 11.2.1 场景模拟
在大批量写入数据（如知识库批量同步或促销导入）时，Java 客户端疯狂报错：`ElasticsearchException[Elasticsearch exception [type=es_rejected_execution_exception...]]`。

#### 11.2.2 排查与解决步骤

* **第一步：排查线程池队列状况**
  检查各节点的写入线程池是否爆满：
  ```bash
  GET /_cat/thread_pool/write?v&h=node_name,active,queue,rejected
  ```
  **诊断要点**：
  * 若 `queue` 达到上限（默认 10000），且 `rejected` 持续增长，说明写入请求速度远远超过了 Data 节点的处理能力。

* **第二步：分析系统资源瓶颈**
  在故障物理机上运行系统监控命令：
  * 运行 `top`：检查 CPU 使用率。如果接近 100% 且 GC 线程占满 CPU，说明正在进行剧烈的垃圾回收或计算。
  * 运行 `iostat -x 1`：检查磁盘 `%util` 是否达到 100%。如果 I/O 堵死，说明机械硬盘或云盘吞吐达到极限。

* **第三步：采取应急降载与调优措施**
  1. **临时将副本数调为 0**（极大降低磁盘 I/O 和 CPU 写入消耗）：
     ```json
     PUT /knowledge_chunks_v1/_settings
     {
       "index.number_of_replicas": 0
     }
     ```
  2. **调大 Refresh 间隔**（把 1 秒一次的刷写合并改为 60 秒一次）：
     ```json
     PUT /knowledge_chunks_v1/_settings
     {
       "index.refresh_interval": "60s"
     }
     ```
  3. **业务端（客户端）立即进行限流与批处理合并**：
     * 停止单条插入，改用 `Bulk API`。
     * 单次 Bulk 大小调整到 **5MB - 10MB**。
     * 减少客户端并行写入的线程并发数。

---

### 11.3 事故三：集群频繁熔断，报错 CircuitBreakingException，JVM 内存居高不下

#### 11.3.1 场景模拟
集群开始疯狂报 `CircuitBreakingException` 错误，查询经常报错拒绝，或者部分节点频繁因内存溢出（OOM）离线重启。

#### 11.3.2 排查与解决步骤

* **第一步：诊断内存开销占比**
  查看各节点 JVM Heap 使用率：
  ```bash
  GET /_cat/nodes?v&h=name,ip,heap.percent,ram.percent,cpu
  ```
  查看是哪个断路器（Breaker）触发了熔断：
  ```bash
  GET /_nodes/stats/breaker
  ```
  **诊断要点**：
  * 若 `fielddata` 熔断器内存占用极高，说明有 `text` 字段被误用于排序或聚合，导致大量 Fielddata 加载进堆。
  * 若 `request` 或 `parent` 熔断器触发，说明单次查询聚合消耗了过多内存。

* **第二步：紧急清理内存缓存（应急措施）**
  如果是 Fielddata 撑爆了堆，先执行缓存清理释放内存，防止集群彻底挂掉：
  ```bash
  POST /_cache/clear?fielddata=true
  ```

* **第三步：根治问题的长效调优**
  1. **纠正 Mapping 设计**：检查导致熔断的字段。如果是对 `text` 字段做聚合/排序，必须将其类型修改为 `keyword`（或使用其 `.keyword` 子字段）。
  2. **限制高基数聚合**：避免在类似用户 ID、UUID 等包含数亿不同值的字段上进行 `terms` 聚合。如果必须做，改用 `composite` 聚合进行分页提取。
  3. **控制集群分片数**：很多时候内存居高不下是因为 Segment 数量过多。检查集群总分片数，下线或合并长期不写入的历史索引：
     ```bash
     # 强制合并小 Segment（大索引在写入期间禁用此命令）
     POST /old_index_2025/_forcemerge?max_num_segments=1
     ```

---

### 11.4 事故四：查询性能骤降，响应时间变长，频繁出现 Slow Log

#### 11.4.1 场景模拟
用户反馈搜索页面转圈，经常出现 504 门户超时。

#### 11.4.2 排查与解决步骤

* **第一步：定位慢查询语句**
  如果已经在索引设置中配置了慢日志（Slow Log），直接去 ES 的日志目录（如 `/data/elasticsearch/logs/`）下查看 `*_index_search_slowlog.log` 文本文件。
  如果没有开启，执行以下命令开启慢查询日志：
  ```json
  PUT /_settings
  {
    "index.search.slowlog.threshold.query.warn": "2s",
    "index.search.slowlog.threshold.query.info": "1s"
  }
  ```

* **第二步：分析慢查询的执行计划**
  在 Kibana Dev Tools 中，将定位到的慢查询 DSL 语句带入 `_explain` 或 `profile` 进行分析：
  ```json
  GET /knowledge_chunks_read/_search
  {
    "profile": true,
    "query": {
      "match": { "chunkText": "Spring Boot 核心原理" }
    }
  }
  ```
  **诊断要点**：
  * 查看哪个 Shard 的耗时最长。
  * 检查是否由于 `match` 查询中的分词数量过多，或者执行了复杂的 `script` 脚本计算。

* **第三步：优化 DSL 编写**
  1. **用 Filter 代替 Query**：不需要相关性打分的过滤条件（如状态、分类、租户 ID），全部从 `must` 移入 `filter`。ES 会自动缓存 filter 结果（Filter Cache），下次查询时直接跳过评分且命中缓存。
  2. **消灭深分页**：检查客户端是否写了 `from: 10000, size: 20` 这样的深分页语句。深分页在分布式 ES 中需要协调节点拉取所有分片的前 10020 条数据在内存中重新排序，开销极大。
     * *优化*：强制禁止用户翻页超过 100 页。如果业务需要导出或拉取全部数据，必须修改客户端改用 **`search_after`** 滚动检索。
  3. **避免前缀通配符查询**：严禁在生产中使用 `{"wildcard": {"title": "*java"}}` 这种以星号开头的模糊匹配，这会导致全表扫描。

---

## 十二、面试高频回答模板

### 12.1 ES 为什么适合搜索

> ES 底层基于倒排索引，把"文档包含哪些词"转成"词出现在哪些文档"，所以全文检索时能快速召回候选文档，再进行过滤和相关性排序。倒排索引的 Posting List 还使用了 FOR 压缩和 Roaring Bitmap 做交集/并集运算，所以多条件组合查询也很快。

### 12.2 ES 为什么是近实时

> 文档写入后先进入内存 Buffer 并写 Translog，默认每 1 秒执行 Refresh 生成新的可搜索 Segment（放在 OS Page Cache），搜索才能看到这批数据。所以 ES 是近实时（NRT），不是严格实时。如果业务需要写完立刻可搜，可以手动调用 `_refresh`，但不建议高频使用。

### 12.3 text 和 keyword 区别

> `keyword` 存原样值，不分词，适合精确匹配、排序和聚合；`text` 存分词后的 token，适合全文搜索。工程上常用 `fields` 多字段映射，同一个字段 `title` 走全文搜索，`title.keyword` 走精确匹配和聚合。选错了类型，聚合结果不符合预期或者全文搜索召回为零。

### 12.4 深分页为什么慢

> `from + size` 深分页会让每个分片取大量候选结果，再由协调节点归并排序，页数越深浪费越大。ES 默认限制最大翻页 10000 条。深翻页更适合用 `search_after`（基于排序游标），大批量导出用 Scroll。

### 12.5 MySQL 和 ES 一致性怎么做

> 通常 MySQL 做事实数据源，ES 做搜索视图，通过 MQ 或 Binlog（Canal/Debezium）同步实现最终一致。写入 ES 失败时要有重试队列、补偿任务和对账机制，不能把 ES 当强一致主库。

### 12.6 集群脑裂是什么、怎么防

> 脑裂指网络分区导致集群分裂成两个独立的子集群，各自选出 Master 并接受写入，数据不一致。ES 7.x+ 通过法定多数（Quorum）机制自动防护：Master 候选节点需要获得过半票数才能当选 Master。3 个 Master 候选节点最多容忍 1 个节点故障。所以 Master 候选节点数建议设为奇数（3 或 5）。

### 12.7 集群 Yellow 怎么排查

> 先看 `_cluster/health` 确认状态，然后用 `_cat/shards` 找到未分配的副本分片，再用 `_cluster/allocation/explain` 查看未分配原因。常见原因包括：副本数超过可用节点数、磁盘水位超 85%、节点刚重启恢复中。对应解决：减少副本或增加节点、清理磁盘、等待恢复。

### 12.8 ES 怎么做向量搜索

> ES 8.x 原生支持 `dense_vector` 字段类型和 kNN 搜索。文本通过 Embedding 模型转成高维向量存入 ES，查询时对问题也做 Embedding，再用 kNN 基于 HNSW 索引做近似最近邻搜索，毫秒级返回 topK 语义最相似的文档。

### 12.9 HNSW 索引是什么

> HNSW 是一种分层图索引。高层节点稀疏、连接跨度大，负责快速粗定位；低层节点密集，负责精确搜索。查询从最高层贪心搜索逐层下降到 Layer 0，时间复杂度接近 O(logN)，是 ES 向量搜索性能的基础。

### 12.10 为什么 RAG 场景选 ES 而不是专用向量库

> 知识库规模在百万级以内时，ES 向量检索性能和专用库无差距，而且 ES 天然支持混合搜索（向量 + 关键词 + 过滤），团队有运维经验，不需要额外引入中间件。如果后续规模到亿级或对延迟有极致要求再考虑迁移。

---

## 十三、ES 8.x 向量搜索与 RAG 场景

ES 8.x 最大的变化之一是原生支持向量搜索。这让 ES 从纯文本搜索引擎进化为可以同时做文本检索和语义检索的混合搜索平台，也是 RAG（检索增强生成）架构中向量存储的热门选型。

### 13.1 向量搜索解决什么问题

传统搜索靠关键词匹配，遇到语义近似但关键词不同的情况就无能为力：

```text
用户搜索："怎么让 Redis 数据不丢"
文档内容："Redis 的 RDB 持久化和 AOF 持久化机制"
```

关键词完全不重叠，`match` 查询召回为零。但这两段话语义高度相关。向量搜索就是解决这个问题的——把文本转成高维向量，用向量之间的几何距离衡量语义相似度。

### 13.2 dense_vector 字段类型

ES 8.x 用 `dense_vector` 字段存储向量：

```json
PUT /knowledge_chunks
{
  "mappings": {
    "properties": {
      "chunk_text": { "type": "text", "analyzer": "ik_max_word" },
      "chunk_vector": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine"
      },
      "doc_id": { "type": "keyword" },
      "knowledge_base_id": { "type": "keyword" },
      "heading_path": { "type": "keyword" }
    }
  }
}
```

关键参数：

| 参数 | 说明 |
| --- | --- |
| `dims` | 向量维度，必须和 Embedding 模型输出一致（如 bge-m3 是 1024） |
| `index` | `true` 启用 HNSW 索引，支持 kNN 搜索；`false` 只能暴力扫描 |
| `similarity` | 相似度算法：`cosine`（余弦，最常用）、`dot_product`（点积）、`l2_norm`（欧氏距离） |

`dims` 一旦设定不能改，改维度需要重建索引。

### 13.3 kNN 搜索

向量写入后，用 kNN 查询找语义最相似的 topK 文档：

```json
POST /knowledge_chunks/_search
{
  "knn": {
    "field": "chunk_vector",
    "query_vector": [0.012, -0.034, ..., 0.056],
    "k": 5,
    "num_candidates": 50
  }
}
```

| 参数 | 说明 |
| --- | --- |
| `field` | dense_vector 字段名 |
| `query_vector` | 查询文本经 Embedding 模型生成的向量 |
| `k` | 返回的最相似文档数 |
| `num_candidates` | 每个分片采集的候选数，越大召回越准但越慢，一般设为 k 的 5~10 倍 |

kNN 搜索的返回结果自带 `_score`，分数越高语义越相似。

### 13.4 混合搜索：向量 + 传统检索

ES 8.x 支持把 kNN 和传统 query 组合在一起，这是比纯向量搜索更实用的模式：

```json
POST /knowledge_chunks/_search
{
  "knn": {
    "field": "chunk_vector",
    "query_vector": [0.012, -0.034, ..., 0.056],
    "k": 5,
    "num_candidates": 50
  },
  "query": {
    "bool": {
      "must": [
        { "match": { "chunk_text": "Redis 持久化" } }
      ],
      "filter": [
        { "term": { "knowledge_base_id": "kb_001" } }
      ]
    }
  },
  "size": 10
}
```

这种写法下 ES 会同时执行 kNN 和 bool query，两者的结果通过 Reciprocal Rank Fusion（RRF）合并排序。关键词能命中的文档和语义相关的文档都能被召回，覆盖面更广。

工程上还有一种用法：用 `filter` 做预过滤再做 kNN，比如限定知识库 ID、文档状态等，缩小向量搜索范围：

```json
"knn": {
  "field": "chunk_vector",
  "query_vector": [...],
  "k": 5,
  "num_candidates": 50,
  "filter": { "term": { "knowledge_base_id": "kb_001" } }
}
```

### 13.5 HNSW 索引原理

ES 8.x 的 kNN 底层用 HNSW（Hierarchical Navigable Small World）算法，面试高频考点。

**直觉理解**：想象你要在一个城市里找一家特定的咖啡店。你不会挨家挨户找（暴力扫描），而是先看大区域路标（高层图），确定大致片区，再看小街道标识（低层图），逐步逼近目标。

HNSW 的核心思想：

```text
Layer 2（最稀疏）:  A -------- D                    跳得远，粗定位
                    |           |
Layer 1（中等）:    A --- B --- D --- F              中等粒度
                    |     |     |     |
Layer 0（最密）:    A-B-C-D-E-F-G-H-I-J            逐个比较，精确定位
```

构建时，每个向量节点被随机分配到若干层。高层节点稀疏、连接跨度大，负责快速粗定位；低层节点密集、连接短，负责精确搜索。查询从最高层入口开始贪心搜索，逐层下降，最终在 Layer 0 找到最近邻。

关键参数：

| 参数 | 含义 | 调优方向 |
| --- | --- | --- |
| `m` | 每层每个节点的最大连接数 | m 大 → 召回率高，内存占用大 |
| `ef_construction` | 构建索引时的搜索宽度 | 大 → 索引质量高，构建慢 |
| `ef_search`（查询时） | 搜索时的候选队列大小 | 大 → 召回率高，查询慢 |

ES 默认 `m=16, ef_construction=100`，对大多数场景够用。

### 13.6 内存开销估算

HNSW 索引需要常驻堆外内存（off-heap），这是向量搜索的主要成本：

```text
单条向量内存 ≈ dims × 4 bytes（float32） × HNSW 系数（约 2~3 倍）
```

以 bge-m3（1024 维）为例：

| 数据量 | 向量原始大小 | HNSW 索引约占用 |
| --- | --- | --- |
| 10 万条 | ~400MB | ~1GB |
| 100 万条 | ~4GB | ~10GB |
| 1000 万条 | ~40GB | ~100GB |

学习阶段几千条 chunk 数据，内存开销可以忽略。生产环境百万级以上需要考虑节点内存规划。

### 13.7 Embedding 接入流程

向量搜索的前提是有 Embedding 模型把文本转成向量。典型的 RAG 写入和查询流程：

```text
【写入流程】
原始文档
  -> 文档解析（Markdown/PDF/Word）
  -> 文本切分（按标题/段落，控制 chunk 大小）
  -> 调 Embedding API（如 bge-m3）得到 1024 维向量
  -> 写入 ES（chunk_text + chunk_vector + 元数据）

【查询流程】
用户提问
  -> 调 Embedding API 把问题转成向量
  -> ES kNN 搜索找 topK 最相似的 chunk
  -> 把 chunk 文本作为上下文拼进 Prompt
  -> 调 LLM 生成带引用的回答
```

这就是 RAG 的完整链路，ES 在其中承担向量存储和语义检索的角色。

### 13.8 ES 向量搜索 vs 专用向量数据库

| 维度 | ES 8.x | Milvus / Qdrant |
| --- | --- | --- |
| 向量检索性能 | 百万级内无差距 | 亿级场景更优 |
| 混合搜索 | 天然支持（kNN + bool + agg） | 需要额外集成 |
| 全文检索 | 原生强项 | 不支持或很弱 |
| 运维复杂度 | 已有 ES 集群即可复用 | 新增中间件 |
| 生态成熟度 | 监控、备份、分片管理完善 | 相对年轻 |
| 适用场景 | 中小规模 RAG、企业知识库 | 大规模纯向量检索 |

面试表达：

> 我们选 ES 做向量存储是因为知识库规模在百万级以内，ES 原生支持混合搜索（向量 + 关键词 + 过滤），而且团队已有 ES 运维经验，不需要额外引入专用向量库增加系统复杂度。如果后续向量规模到亿级或对延迟有极致要求，可以考虑迁移到 Milvus。

### 13.9 向量搜索常见面试追问

**Q：cosine 和 dot_product 区别？**
cosine 衡量的是方向相似度（归一化后等价于点积），不受向量长度影响；dot_product 要求向量先归一化，ES 会做优化跳过除法。如果 Embedding 模型输出的向量已经归一化，用 `dot_product` 性能更好。

**Q：num_candidates 设多少合适？**
经验值是 k 的 5~10 倍。设太小召回不够（可能漏掉真正相似的文档），设太大性能下降。生产上一般先设 10 倍，根据实际召回效果微调。

**Q：向量字段能更新吗？**
可以。对同一个 document ID 做 index 操作会覆盖旧向量。但维度不能变，变维度必须重建索引。

**Q：kNN 和 script_score 暴力搜索区别？**
kNN 走 HNSW 索引，近似最近邻，速度快但结果不是 100% 精确；script_score 是全量扫描精确计算距离，数据量大时极慢。生产环境用 kNN，调试或小数据量可以用 script_score 做 baseline 对比。

---

## 最后建议

ES 面试最值钱的一条线是：

> 倒排索引为什么适合搜索，写入为什么近实时，分片副本怎么影响性能，Mapping 为什么要提前设计，深分页和聚合为什么容易慢，集群节点角色和脑裂防护机制，ES 8.x 怎么做向量搜索以及为什么 RAG 场景选 ES。

这条线讲顺，ES 就能从"会写 DSL"升级成"懂搜索系统工程边界"，再升级到"懂集群治理与向量检索架构"。
