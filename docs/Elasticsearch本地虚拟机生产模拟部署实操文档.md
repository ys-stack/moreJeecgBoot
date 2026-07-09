# Elasticsearch 真实生产环境部署与优化实操文档（针对 256G 内存 / 4核 / 2T 硬盘服务器）

> **文档定位**：本指南针对企业级生产环境进行编写。所有服务器拥有相同的硬件规格：**256G 内存、4 核 CPU、2T 硬盘（建议 SSD）**。
> 本文档提供 **一键自动化部署脚本** 与 **全套 8 节点独立配置文件**，支持直接复制粘贴运行，快速拉起 **3 Master + 3 Data + 2 Coordinating** 的高可用 ES 安全集群。

---

## 目录

- [一、 生产拓扑与服务器规划](#一-生产拓扑与服务器规划)
- [二、 内存深度调优：31G Heap 与 Compressed OOPs 原理](#二-内存深度调优31g-heap-与-compressed-oops-原理)
- [三、 4核 CPU 的性能防挂与写入调优](#三-4核-cpu-性能防挂与写入调优)
- [四、 2T 硬盘水位线（Watermark）规划](#四-2t-硬盘水位线watermark规划)
- [五、 全套 8 节点独立配置文件（手动部署备用）](#五-全套-8-节点独立配置文件手动部署备用)
- [六、 自动化一键部署方案（极力推荐）](#六-自动化一键部署方案极力推荐)
- [七、 节点滚动引导与启动顺序](#七-节点滚动引导与启动顺序)
- [八、 负载均衡与安全连接](#八-负载均衡与安全连接)
- [九、 RAG 向量检索优化方案](#九-rag-向量检索优化方案)
- [十、 集群监控、慢日志与快照备份](#十-集群监控慢日志与快照备份)
- [十一、 故障演练预案](#十一-故障演练预案)
- [十二、 生产禁忌清单](#十二-生产禁忌清单)
- [十三、 面试与项目答辩话术](#十三-面试与项目答辩话术)

---

## 一、 生产拓扑与服务器规划

### 1.1 拓扑设计
针对 **256G 内存 / 4 核 CPU / 2T 硬盘** 的“大内存、低算力”特征，我们将 8 台服务器分配给以下角色：

* **3 个 Dedicated Master（专用主节点）**：仅负责集群管理与元数据维护，不存数据，不接受业务读写。
* **3 个 Dedicated Data（专用数据节点）**：负责存储分片、索引写入和耗费内存的 HNSW 向量检索。
* **2 个 Coordinating-only（协调路由节点）**：作为业务流量入口，负责分发请求并聚合、排序和合并结果。

```text
                     客户端 / Java 业务服务 (JeecgBoot)
                                     |
                             内网高可用 SLB / Nginx
                                     |
                       +-------------+-------------+
                       |                           |
                 es-coord-01                 es-coord-02
                 IP: 192.168.1.31            IP: 192.168.1.32
                 (协调路由节点)               (协调路由节点)
                       |                           |
                       +-------------+-------------+
                                     |
         +---------------------------+---------------------------+
         |                           |                           |
    es-data-01                  es-data-02                  es-data-03
    IP: 192.168.1.21            IP: 192.168.1.22            IP: 192.168.1.23
    (数据/ingest节点)           (数据/ingest节点)           (数据/ingest节点)
         |                           |                           |
         +---------------------------+---------------------------+
                                     |
         +---------------------------+---------------------------+
         |                           |                           |
   es-master-01                es-master-02                es-master-03
   IP: 192.168.1.11            IP: 192.168.1.12            IP: 192.168.1.13
   (专用主节点)                 (专用主节点)                 (专用主节点)
```

### 1.2 服务器规划详情

| 主机名 | 节点 IP | 节点角色 | CPU | 物理内存 | JVM Heap | 2T 硬盘分配 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **es-master-01** | `192.168.1.11` | master | 4C | 256G | **31G** | 50G 用于 ES | 专用主节点 |
| **es-master-02** | `192.168.1.12` | master | 4C | 256G | **31G** | 50G 用于 ES | 专用主节点 |
| **es-master-03** | `192.168.1.13` | master | 4C | 256G | **31G** | 50G 用于 ES | 专用主节点 |
| **es-data-01** | `192.168.1.21` | data, ingest | 4C | 256G | **31G** | 1.8T 用于数据 | 数据存储与向量检索 |
| **es-data-02** | `192.168.1.22` | data, ingest | 4C | 256G | **31G** | 1.8T 用于数据 | 数据存储与向量检索 |
| **es-data-03** | `192.168.1.23` | data, ingest | 4C | 256G | **31G** | 1.8T 用于数据 | 数据存储与向量检索 |
| **es-coord-01** | `192.168.1.31` | 协调路由 (`[]`) | 4C | 256G | **31G** | 50G 用于日志缓存 | 业务流量接入与聚合 |
| **es-coord-02** | `192.168.1.32` | 协调路由 (`[]`) | 4C | 256G | **31G** | 50G 用于日志缓存 | 业务流量接入与聚合 |
| **es-ops-01** | `192.168.1.41` | Kibana/Nginx/控制台 | 4C | 256G | - | 1.5T 用于备份 | 运维管理机（非 ES 节点） |

---

## 二、 内存深度调优：31G Heap 与 Compressed OOPs 原理

### 2.1 为什么堆内存限制在 31G？
1. **Compressed OOPs（普通对象指针压缩）机制**：
   * 64 位 JVM 默认使用 8 字节指针寻址，这会导致内存消耗增加、CPU 缓存利用率下降。
   * 为了优化，JVM 在堆内存小于约 32GB 时，将对象以 8 字节对齐，并使用 32 位压缩指针（Compressed OOPs），最大寻址空间为 $2^{32} \times 8 = 32\text{GB}$。
   * 一旦 JVM 堆大小设置为 `32g` 或以上，压缩指针失效，JVM 退回使用 64 位指针。
   * **结果**：一个 35GB 的 JVM 堆，由于指针膨胀和额外的对象头开销，其实际能容纳的有效 Java 对象数量反而不如 31GB。
2. **垃圾回收（GC）延迟**：
   * 堆内存越大，GC 扫描的对象越多。在 4 核 CPU 下，过大的堆内存会导致极长的垃圾回收停顿时间。

### 2.2 剩余 225G 内存的用处
* **Lucene Page Cache（页缓存）**：用于将磁盘上的只读索引段（Segments）缓存到物理内存中，读操作几乎不需要物理 I/O。
* **HNSW 向量索引堆外内存（Off-heap）**：RAG 场景下的向量数据索引（HNSW）是直接向操作系统申请堆外内存的。225G 物理内存能保证数亿条向量索引全部加载到内存中，避免磁盘寻道瓶颈。

---

## 三、 4核 CPU 的性能防挂与写入调优

4 核 CPU 是集群的性能短板。在写入压力较大时，必须进行防挂起与降载优化：

1. **`index.refresh_interval` 调大**：
   * 将 refresh 间隔调大到 **`30s`** 或 **`60s`**。减少 Lucene 段的产生，降低 Segment Merge 带来的 CPU 负荷。
2. **Translog 异步落盘**：
   * 将 `index.translog.durability` 设为 `async`。减少每次写入时强制 fsync 磁盘对 CPU I/O 等待时间的消耗。
3. **写入限流与批量（Bulk）优化**：
   * 业务侧（Java 客户端）每次 Bulk 的包大小控制在 **5MB - 10MB**（单次约 1000~2000 条），并发线程控制在 2~3 个，严禁高并发无限制写入。

---

## 四、 2T 硬盘水位线（Watermark）规划

对于 2TB 硬盘，使用默认的百分比限制（85% / 90% / 95%）在磁盘空间富余时不够精准。建议在集群中将其配置为**绝对值水位线**：
* **低水位（Low Watermark）**：`150gb`。剩余磁盘不足 150G 时，不再向该节点分派新的分片。
* **高水位（High Watermark）**：`80gb`。剩余磁盘不足 80G 时，开始向其他节点迁移分片。
* **洪水水位（Flood Stage Watermark）**：`40gb`。剩余磁盘不足 40G 时，强制将节点上的所有索引设置为**只读（Read-Only）**，保护底层 Lucene 索引文件不损坏。

---

## 五、 全套 8 节点独立配置文件（手动部署备用）

> 此处提供全部 8 个节点的 `docker-compose.yml` 完整文件。您可以直接复制到对应服务器的 `/opt/elasticsearch/docker-compose.yml` 路径下直接使用。

### 5.1 主节点 (Master) 配置

#### 节点 1：es-master-01 (192.168.1.11)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-master-01
    hostname: es-master-01
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-master-01
      - node.roles=master
      - network.host=192.168.1.11
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - cluster.initial_master_nodes=es-master-01,es-master-02,es-master-03
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-master-01/es-master-01.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-master-01/es-master-01.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-master-01/es-master-01.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-master-01/es-master-01.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

#### 节点 2：es-master-02 (192.168.1.12)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-master-02
    hostname: es-master-02
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-master-02
      - node.roles=master
      - network.host=192.168.1.12
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - cluster.initial_master_nodes=es-master-01,es-master-02,es-master-03
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-master-02/es-master-02.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-master-02/es-master-02.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-master-02/es-master-02.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-master-02/es-master-02.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

#### 节点 3：es-master-03 (192.168.1.13)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-master-03
    hostname: es-master-03
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-master-03
      - node.roles=master
      - network.host=192.168.1.13
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - cluster.initial_master_nodes=es-master-01,es-master-02,es-master-03
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-master-03/es-master-03.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-master-03/es-master-03.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-master-03/es-master-03.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-master-03/es-master-03.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

---

### 5.2 数据节点 (Data) 配置

#### 节点 4：es-data-01 (192.168.1.21)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-data-01
    hostname: es-data-01
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-data-01
      - node.roles=data,ingest
      - network.host=192.168.1.21
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-data-01/es-data-01.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-data-01/es-data-01.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-data-01/es-data-01.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-data-01/es-data-01.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

#### 节点 5：es-data-02 (192.168.1.22)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-data-02
    hostname: es-data-02
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-data-02
      - node.roles=data,ingest
      - network.host=192.168.1.22
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-data-02/es-data-02.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-data-02/es-data-02.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-data-02/es-data-02.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-data-02/es-data-02.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

#### 节点 6：es-data-03 (192.168.1.23)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-data-03
    hostname: es-data-03
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-data-03
      - node.roles=data,ingest
      - network.host=192.168.1.23
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-data-03/es-data-03.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-data-03/es-data-03.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-data-03/es-data-03.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-data-03/es-data-03.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

---

### 5.3 协调路由节点 (Coordinating) 配置

#### 节点 7：es-coord-01 (192.168.1.31)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-coord-01
    hostname: es-coord-01
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-coord-01
      - node.roles=[]
      - network.host=192.168.1.31
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-coord-01/es-coord-01.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-coord-01/es-coord-01.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-coord-01/es-coord-01.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-coord-01/es-coord-01.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

#### 节点 8：es-coord-02 (192.168.1.32)
```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    container_name: es-coord-02
    hostname: es-coord-02
    restart: always
    network_mode: host
    environment:
      - cluster.name=jeecg-ai-es-prod
      - node.name=es-coord-02
      - node.roles=[]
      - network.host=192.168.1.32
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=Change_Me_To_A_Strong_Password_123!
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/es-coord-02/es-coord-02.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/es-coord-02/es-coord-02.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/es-coord-02/es-coord-02.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/es-coord-02/es-coord-02.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
```

---

## 六、 自动化一键部署方案（极力推荐）

为了彻底避免在 8 台机器上手动复制配置文件和传输证书的繁琐与出错可能，以下提供**一键集群部署脚本**。

### 前置条件
1. 将本脚本保存至运维控制节点 `es-ops-01 (192.168.1.41)` 的 `/opt/deploy-cluster.sh` 中。
2. 确保在 `es-ops-01` 上已经配置了至其余 8 台目标机器 root 用户的 **SSH 免密登录**（`ssh-copy-id`）。
3. 确保所有服务器已安装 `docker` 和 `docker-compose-plugin` 插件。

### 一键部署主控脚本 `/opt/deploy-cluster.sh`
直接复制以下全部脚本并运行：

```bash
#!/bin/bash
set -e

# --- 1. 参数定义 ---
PASSWORD="Change_Me_To_A_Strong_Password_123!"
CLUSTER_NAME="jeecg-ai-es-prod"
VERSION="8.17.0"

# 节点列表定义（格式：IP_地址:节点角色:节点名称）
NODES=(
  "192.168.1.11:master:es-master-01"
  "192.168.1.12:master:es-master-02"
  "192.168.1.13:master:es-master-03"
  "192.168.1.21:data:es-data-01"
  "192.168.1.22:data:es-data-02"
  "192.168.1.23:data:es-data-03"
  "192.168.1.31:coord:es-coord-01"
  "192.168.1.32:coord:es-coord-02"
)

echo ">>> [STEP 1] 校验所有节点的 SSH 免密登录状况..."
for item in "${NODES[@]}"; do
  IP=$(echo "$item" | cut -d':' -f1)
  ssh -o ConnectTimeout=3 root@"$IP" "echo 'SSH Connection OK to $IP'"
done

echo ">>> [STEP 2] 在主控机生成 TLS 自签名证书..."
rm -rf /opt/es-certs && mkdir -p /opt/es-certs/certs
cd /opt/es-certs

# 写入 instances.yml
cat > instances.yml <<EOF
instances:
  - name: es-master-01
    dns: ["es-master-01"]
    ip: ["192.168.1.11"]
  - name: es-master-02
    dns: ["es-master-02"]
    ip: ["192.168.1.12"]
  - name: es-master-03
    dns: ["es-master-03"]
    ip: ["192.168.1.13"]
  - name: es-data-01
    dns: ["es-data-01"]
    ip: ["192.168.1.21"]
  - name: es-data-02
    dns: ["es-data-02"]
    ip: ["192.168.1.22"]
  - name: es-data-03
    dns: ["es-data-03"]
    ip: ["192.168.1.23"]
  - name: es-coord-01
    dns: ["es-coord-01"]
    ip: ["192.168.1.31"]
  - name: es-coord-02
    dns: ["es-coord-02"]
    ip: ["192.168.1.32"]
EOF

# 调用临时容器生成证书
docker run --rm -v $(pwd):/certs docker.elastic.co/elasticsearch/elasticsearch:${VERSION} \
  bash -c "elasticsearch-certutil ca --silent --pem -out /certs/ca.zip && unzip -q /certs/ca.zip -d /certs && elasticsearch-certutil cert --silent --pem --ca-cert /certs/ca/ca.crt --ca-key /certs/ca/ca.key --in /certs/instances.yml -out /certs/certs.zip && unzip -q /certs/certs.zip -d /certs"

echo ">>> [STEP 3] 开始对各节点进行宿主机系统优化、分发证书与启动服务..."
for item in "${NODES[@]}"; do
  IP=$(echo "$item" | cut -d':' -f1)
  ROLE=$(echo "$item" | cut -d':' -f2)
  NAME=$(echo "$item" | cut -d':' -f3)

  echo "------------------------------------------------------------"
  echo "正在部署节点: $NAME ($IP) 角色: $ROLE"
  echo "------------------------------------------------------------"

  # 1. 宿主机内核与参数优化
  ssh root@"$IP" "bash -s" <<'EOF'
    # 关闭 Swap
    swapoff -a
    sed -i '/swap/s/^/#/' /etc/fstab

    # 设置内核映射参数
    sysctl -w vm.max_map_count=262144
    echo "vm.max_map_count=262144" > /etc/sysctl.d/99-elasticsearch.conf
    echo "fs.file-max=1048576" >> /etc/sysctl.d/99-elasticsearch.conf
    sysctl --system

    # 安全限制设置
    cat > /etc/security/limits.d/99-elasticsearch.conf <<'LIMITS'
elasticsearch soft memlock unlimited
elasticsearch hard memlock unlimited
elasticsearch soft nofile 655350
elasticsearch hard nofile 655350
elasticsearch soft nproc 4096
elasticsearch hard nproc 4096
LIMITS

    # 创建工作目录
    mkdir -p /data/elasticsearch/data
    mkdir -p /data/elasticsearch/logs
    mkdir -p /data/elasticsearch/certs
    mkdir -p /opt/elasticsearch
EOF

  # 2. 将对应的证书文件拷贝至远端节点
  scp -r /opt/es-certs/ca root@"$IP":/data/elasticsearch/certs/
  scp -r /opt/es-certs/"$NAME" root@"$IP":/data/elasticsearch/certs/
  ssh root@"$IP" "chown -R 1000:1000 /data/elasticsearch && chmod -R 750 /data/elasticsearch"

  # 3. 根据节点角色动态生成并写入专属的 docker-compose.yml 文件
  # 配置 node.roles 参数
  if [ "$ROLE" == "master" ]; then
    ROLES_CFG="master"
  elif [ "$ROLE" == "data" ]; then
    ROLES_CFG="data,ingest"
  else
    ROLES_CFG="[]"
  fi

  # 主节点专有的初始化启动配置
  INITIAL_MASTERS=""
  if [ "$ROLE" == "master" ]; then
    INITIAL_MASTERS="- cluster.initial_master_nodes=es-master-01,es-master-02,es-master-03"
  fi

  # 将 compose 结构推送到远端服务器
  ssh root@"$IP" "cat > /opt/elasticsearch/docker-compose.yml" <<EOF
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:${VERSION}
    container_name: ${NAME}
    hostname: ${NAME}
    restart: always
    network_mode: host
    environment:
      - cluster.name=${CLUSTER_NAME}
      - node.name=${NAME}
      - node.roles=${ROLES_CFG}
      - network.host=${IP}
      - transport.port=9300
      - http.port=9200
      - discovery.seed_hosts=192.168.1.11:9300,192.168.1.12:9300,192.168.1.13:9300
      ${INITIAL_MASTERS}
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=-Xms31g -Xmx31g
      - ELASTIC_PASSWORD=${PASSWORD}
      - xpack.security.enabled=true
      - xpack.security.transport.ssl.enabled=true
      - xpack.security.transport.ssl.verification_mode=certificate
      - xpack.security.transport.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.transport.ssl.certificate=/usr/share/elasticsearch/config/certs/${NAME}/${NAME}.crt
      - xpack.security.transport.ssl.key=/usr/share/elasticsearch/config/certs/${NAME}/${NAME}.key
      - xpack.security.http.ssl.enabled=true
      - xpack.security.http.ssl.certificate_authorities=/usr/share/elasticsearch/config/certs/ca/ca.crt
      - xpack.security.http.ssl.certificate=/usr/share/elasticsearch/config/certs/${NAME}/${NAME}.crt
      - xpack.security.http.ssl.key=/usr/share/elasticsearch/config/certs/${NAME}/${NAME}.key
    volumes:
      - /data/elasticsearch/data:/usr/share/elasticsearch/data
      - /data/elasticsearch/logs:/usr/share/elasticsearch/logs
      - /data/elasticsearch/certs:/usr/share/elasticsearch/config/certs:ro
    ulimits:
      memlock: { soft: -1, hard: -1 }
      nofile: { soft: 655350, hard: 655350 }
EOF

done

echo ">>> [STEP 4] 开始按顺序启动集群..."
# 1. 启动 Master 节点
echo "正在启动 3 个 Master 专用节点..."
for ip in 192.168.1.11 192.168.1.12 192.168.1.13; do
  ssh root@"$ip" "cd /opt/elasticsearch && docker compose up -d"
done

echo "等待 25秒 确保 Master 完成初始化选主..."
sleep 25

# 2. 启动 Data 节点
echo "正在启动 3 个 Data 数据存储节点..."
for ip in 192.168.1.21 192.168.1.22 192.168.1.23; do
  ssh root@"$ip" "cd /opt/elasticsearch && docker compose up -d"
done

# 3. 启动 Coordinating 节点
echo "正在启动 2 个 Coordinating 协调路由节点..."
for ip in 192.168.1.31 192.168.1.32; do
  ssh root@"$ip" "cd /opt/elasticsearch && docker compose up -d"
done

echo ">>> [FINISH] 集群部署命令已发送完毕！"
echo "请稍后在控制机执行以下验证命令检查集群状态："
echo "curl -k -u elastic:${PASSWORD} https://192.168.1.31:9200/_cluster/health?pretty"
```

---

## 七、 节点滚动引导与启动顺序

如果您是手动部署（通过第五章节的配置文件），必须保证以下顺序：
1. **先启动 3 个 Master 节点**。查看任意 Master 容器日志确认 `master node changed` 选主成功。
2. **首次选主成功后**，修改这 3 个 Master 的配置，**删除（或注释掉） `cluster.initial_master_nodes` 环境变量**，防止将来意外重新初始化选主。
3. **启动 3 个 Data 节点**，允许它们加入由 Master 统一维护的集群元数据中。
4. **启动 2 个 Coordinating 节点**。
5. **部署 Kibana 与业务 SLB 负载均衡**。

---

## 八、 负载均衡与安全连接

为了保障高可用，业务端（如 JeecgBoot 平台）禁止直连 Data 节点，更禁止直连 Master 节点。所有流量均通过负载均衡（如 SLB、Nginx 或 HAProxy）转发给 2 个 Coordinating-only 协调路由节点。

### 8.1 Nginx 负载均衡配置（七层反向代理 + SSL 卸载）
在运维管理机 `192.168.1.41` 上配置 Nginx，接收 9200 端口请求并负载均衡至 Coordinating 节点：

```nginx
# /etc/nginx/conf.d/elasticsearch.conf
upstream es_coordinating_nodes {
    server 192.168.1.31:9200 max_fails=3 fail_timeout=10s;
    server 192.168.1.32:9200 max_fails=3 fail_timeout=10s;
}

server {
    listen 9200 ssl;
    server_name es-search.yourdomain.com;

    # 业务服务到 Nginx 的 HTTPS 证书
    ssl_certificate     /etc/nginx/certs/es_nginx.crt;
    ssl_certificate_key /etc/nginx/certs/es_nginx.key;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location / {
        proxy_pass https://es_coordinating_nodes;
        proxy_ssl_verify off; # 如果 ES 使用自签名证书且不需要校验则设为 off
        
        # 基础代理配置
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        
        # 针对大批量写入（Bulk）调大超时时间
        proxy_connect_timeout 5s;
        proxy_read_timeout 90s;
        proxy_send_timeout 90s;
    }
}
```

### 8.2 生产级业务账号与权限细粒度限制
不要让 JeecgBoot 业务或者 RAG 后端使用 `elastic` 超级账号登录。在 Kibana Console 中执行以下命令创建最小权限用户：

```json
// 1. 创建 RAG 业务专属角色
POST /_security/role/jeecg_rag_prod_role
{
  "cluster": ["monitor"],
  "indices": [
    {
      "names": ["knowledge_chunks*", "product_search*"],
      "privileges": ["read", "write", "create_index", "view_index_metadata", "manage"]
    }
  ]
}

// 2. 创建用户并绑定角色
POST /_security/user/jeecg_rag_client
{
  "password": "Keep_This_To_A_Very_Strong_Password_999!",
  "roles": ["jeecg_rag_prod_role"],
  "full_name": "JeecgBoot RAG Service User",
  "email": "rag-admin@yourdomain.com"
}
```

---

## 九、 RAG 向量检索优化方案

在 RAG 场景中，4 核 CPU 处理 1024 维的大规模向量计算压力极大。必须优化映射（Mapping）和查询设计：

### 9.1 向量索引与数据模型映射
以 bge-m3 产生的 1024 维向量为例，在协调节点上创建索引：

```json
PUT /knowledge_chunks_v1
{
  "settings": {
    "index": {
      "number_of_shards": 3,           # 3个主分片刚好均匀落到 3个 Data 节点上
      "number_of_replicas": 1,         # 1个副本分片，提供高可用并分担查询 QPS
      "refresh_interval": "30s",       # 调大刷新间隔，降低段合并时的 CPU 占用
      "translog.durability": "async"   # 异步刷盘 translog（提升批量写入性能，防 IO 阻塞）
    }
  },
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "knowledgeBaseId": { "type": "keyword" },
      "tenantId": { "type": "keyword" },
      "chunkText": { "type": "text", "index": false }, # 堆内只存，不分词，减小 CPU 开销
      "createTime": { "type": "date" },
      "chunkVector": {
        "type": "dense_vector",
        "dims": 1024,
        "index": true,
        "similarity": "cosine",
        "index_options": {
          "type": "hnsw",
          "m": 16,                     # HNSW 每个节点的最大连接数（生产建议 16-32）。越大召回越高但更费 CPU
          "ef_construction": 100       # 构建索引时的搜索深度（生产建议 100-200）。数值越大构建越慢，但召回效果越好
        }
      }
    }
  }
}
```

### 9.2 创建版本别名（Alias）
```json
POST /_aliases
{
  "actions": [
    { "add": { "index": "knowledge_chunks_v1", "alias": "knowledge_chunks_read" } },
    { "add": { "index": "knowledge_chunks_v1", "alias": "knowledge_chunks_write", "is_write_index": true } }
  ]
}
```

### 9.3 权限隔离的 knn 查询（学习重点）
RAG 查询必须避免越权检索，查询必须强制带 filter，以直接在 Lucene 页缓存层过滤非当前租户、非当前知识库的数据，避免不必要的向量距离计算：

```json
POST /knowledge_chunks_read/_search
{
  "knn": {
    "field": "chunkVector",
    "query_vector": [0.0125, -0.0084, 0.0543, "...此处省略共1024维数据..."],
    "k": 5,
    "num_candidates": 50,              # 在每个分片检索的候选集大小。在 4 核 CPU 下不宜设得过大（控制在 50-100），防止检索 CPU 耗尽
    "filter": {
      "bool": {
        "must": [
          { "term": { "tenantId": "tenant_company_01" } },
          { "term": { "knowledgeBaseId": "kb_engineering_manual" } }
        ]
      }
    }
  },
  "_source": ["id", "knowledgeBaseId", "chunkText"]
}
```

---

## 十、 集群监控、慢日志与快照备份

### 10.1 集群健康状态排查命令
使用 HTTP API 快速检查集群的运行指标：

```bash
# 1. 检查健康状况与节点数是否对齐（正常应返回 status: green, number_of_nodes: 8, number_of_data_nodes: 3）
curl -k -u elastic:Change_Me_To_A_Strong_Password_123! https://192.168.1.31:9200/_cluster/health?pretty

# 2. 查看节点列表与角色
curl -k -u elastic:Change_Me_To_A_Strong_Password_123! "https://192.168.1.31:9200/_cat/nodes?v&h=name,ip,node.role,master,heap.percent,ram.percent,cpu"

# 3. 详细排查为什么分片无法分配（如有 Unassigned Shards 时）
curl -k -u elastic:Change_Me_To_A_Strong_Password_123! https://192.168.1.31:9200/_cluster/allocation/explain?pretty
```

### 10.2 慢日志（Slowlog）开启与优化
为向量和业务索引开启慢日志记录，及时排查导致 4 核 CPU 负载升高的“慢查询”：

```json
PUT /knowledge_chunks_v1/_settings
{
  "index.search.slowlog.threshold.query.warn": "2s",      # 检索阶段超过 2秒 记录 warn 日志
  "index.search.slowlog.threshold.query.info": "800ms",   # 检索阶段超过 800毫秒 记录 info 日志
  "index.search.slowlog.threshold.fetch.warn": "1s",      # 取回阶段超过 1秒 记录 warn
  "index.indexing.slowlog.threshold.index.warn": "3s"     # 写入阶段超过 3秒 记录 warn
}
```

### 10.3 生产级快照（Snapshot）备份
快照是数据的终极防线，不能只指望 Replica（副本只能防单节点宕机，防不住误删操作）。
1. 挂载 NFS 网络共享存储到 3 个 Data 节点及 `es-ops-01`，挂载点为 `/mnt/es_snapshots`。
2. 注册快照仓库：
```json
PUT /_snapshot/es_backup_repo
{
  "type": "fs",
  "settings": {
    "location": "/mnt/es_snapshots",
    "compress": true
  }
}
```
3. 创建备份快照：
```json
PUT /_snapshot/es_backup_repo/snapshot_20260709?wait_for_completion=true
```

---

## 十一、 故障演练预案

在非业务高峰期执行以下故障演练，验证系统的弹性和高可用性。

### 11.1 模拟 Master 宕机（停掉 `es-master-01`）
1. 在 `192.168.1.11` 上停止 Master 容器：`docker compose stop elasticsearch`
2. **观察表现**：
   * 业务请求仍能通过 Nginx/SLB 正常写入和检索（因为 Coordinating 节点正常）。
   * `es-master-02` 和 `es-master-03` 会在秒级内发起选主操作，选出新的主节点。
   * 查看集群健康度从 `green` 变为 `green`（但 Master 存活节点数从 3 变为 2）。
3. **恢复**：重启 `es-master-01` 容器，其将作为 Candidate Master 重新加入集群，不触发任何数据重平衡（因为其本身不带分片）。

### 11.2 模拟 Data 节点宕机（停掉 `es-data-02`）
1. 停止该节点：`docker compose stop elasticsearch`
2. **观察表现**：
   * 集群状态立即变为 `yellow`（因为有些 Primary 分片对应的 Replica 缺失了，或是 Replica 升为主分片后缺失了副本）。
   * 集群不会立即迁移数据（有 `index.unassigned.node_left.delayed_timeout` 机制，默认延迟 1 分钟，防止网络瞬间抖动引发大规模不必要的数据迁移）。
   * 业务查询没有丢失数据，因为所有分片都有另一份副本在 `es-data-01` 或 `es-data-03` 上。
3. **恢复**：如果在 1 分钟内重启启动 Data 节点，分片自动关联，集群秒级回绿（`green`）。

---

## 十二、 生产禁忌清单

1. **禁忌一**：把 JVM 堆内存（JVM Heap）配置为超过 32GB（例如 `32g`、`64g` 等），这会导致 JVM 指针压缩失效，性能严重受损。
2. **禁忌二**：允许客户端/业务端直连 Master 节点或 Data 节点。所有流量必须强制通过负载均衡转发给 2 个 Coordinating 协调路由节点。
3. **禁忌三**：单台服务器运行多个 ES 生产容器（在 256G 内存物理机上不要在一台机器部署多个 ES 容器以图“压榨”内存。单节点大内存更易维护，且 4 核 CPU 无法支撑多个大 JVM 的频繁 GC 竞争）。
4. **禁忌四**：将 `index.refresh_interval` 长期保留为默认的 `1s`。对于批量向量导入或写入密集型索引，这会引发 CPU merge 阻塞，导致 CPU 满载挂掉。
5. **禁忌五**：在未做任何客户端限流的情况下，发起数万并发的 `bulk` 请求，导致只有 4 核 CPU 的协调节点线程池瞬间占满，触发大量 `Write Reject` 抛弃写入。
6. **禁忌六**：忽略磁盘水位监控。一旦磁盘使用量突破 `95%` 触发只读洪水线，业务写入锁死，会导致 Java 业务系统报大量 403 Blocked 异常。

---

## 十三、 面试与项目答辩话术

### 13.1 怎么介绍你们生产集群的架构设计？
> **话术**：在我们的项目中，ES 生产集群采用了角色分离的物理部署模式。由于服务器规格统一为 256G 内存和 4核 CPU，我们因地制宜配置了 3个专用 Master 节点、3个数据 Data 节点以及 2个协调路由 Coordinating 节点。
> 为了保障绝对的安全与可用性，业务流量（JeecgBoot / RAG 检索）不直连任何数据和主节点，而是通过 Nginx + SLB 高可用网关转发给 2个协调节点。协调节点仅负责请求分发和跨分片聚合排序，不存储数据，有效规避了复杂查询打满 Master 节点的问题。

### 13.2 为什么 256G 的物理内存，你们 JVM Heap 只给 31G？
> **话术**：这是基于 JVM 寻址优化（Compressed OOPs）的考虑。一旦 JVM Heap 设为 32G 或以上，64 位的 JVM 就会失效压缩指针，导致普通对象指针膨胀，使得堆内存的对象存装量反而缩水。同时，大堆内存会极大加重垃圾回收（GC）的停顿延迟。
> 另一方面，我们的应用是 RAG 知识库检索，底层需要维护大量的 HNSW 向量索引。我们将剩余的 225G 物理内存全部留给了操作系统。这部分内存会作为系统的 Page Cache 和堆外缓存（Off-heap），使得 Lucene 段文件和向量数据能够完全常驻内存，检索过程免去了磁盘 I/O，极大地弥补了 4核 CPU 在计算能力上的局限。

### 13.3 在 4 核 CPU 的弱算力下，你们如何解决高并发向量写入造成的 CPU 暴涨？
> **话术**：我们主要在架构和索引配置两个维度做了优化：
> 1. **架构层面**：用 Coordinating 路由节点承载了客户端 HTTP 连接和结果合并的压力；
> 2. **索引层面**：将写入索引的 `refresh_interval` 从默认的 `1s` 调大至 `30s`，同时将 `translog.durability` 设为 `async`（异步刷盘），这极大地减少了 Lucene 段的实时合并频率，大幅降低了多线程 Segment Merge 对 CPU 的消耗；
> 3. **客户端限流**：我们在后端 Java 写入端控制了 bulk 批量大小在 5MB-10MB（约 1000条向量记录），限制并发写入线程数为 2-3 个，并配合副本数在初次导入时设为 0、导入后重建副本的手段，平滑地完成了大规模向量的导入，避免了 CPU 满载引发的节点宕机。