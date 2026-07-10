# Elasticsearch 集群架构升级与数据平滑迁移方案

本篇文档用于指导如何将虚拟机上已建好索引的 **3 节点混合角色集群**，平滑升级为生产级的 **3 专职 Master + 3 专职 Data 节点集群**，并确保已有索引数据完全不丢失。

---

## 一、 现状与升级目标

### 1.1 现状（混合角色集群）
*   **节点数**：3 个节点 (`es01`, `es02`, `es03`)
*   **角色**：各节点默认兼任 `master` (候选主节点) 和 `data` (数据节点)。
*   **数据存储**：数据已保存在本地 `/data/es-lab/es0[1-3]/data` 路径下，索引已建立。

### 1.2 升级目标（职责分离集群）
*   **3 个专职 Master 节点** (`es-master-01` ~ `03`)：只负责集群协调与元数据管理，不存数据。
*   **3 个专职 Data 节点** (`es-data-01` ~ `03`)：负责数据存储和向量检索，继承原先的数据目录。
*   **无缝迁移**：不丢失任何已有索引，不需重新做 Embedding。

---

## 二、 迁移核心原理（目录继承法）

因为 Elasticsearch 依靠数据目录下的 `UUID` 和集群名称（`cluster.name`）来识别集群与分片，所以最安全、平滑的升级手段是**“继承原数据目录”**：
1.  新建 3 个专职 Master 节点，设置它们只担任 `master` 角色，**不分配数据目录**（或分配全新目录）。
2.  将原本的 3 个旧节点重命名为 `es-data-01`, `es-data-02`, `es-data-03`，角色设置为 `data` 和 `ingest`，并**继续挂载原先的 `/data/es-lab/es0[1-3]/data` 数据目录**。
3.  保持 `cluster.name`（如 `es-lab`）以及安全证书不变，启动新集群。ES 将自动识别历史数据并重新分发主备分片。

---

## 三、 平滑升级步骤

### 步骤 1：备份数据（防范万一）
在进行任何大动作之前，先停止写入并对数据目录进行快照备份或物理打包：
```bash
# 停止 Java 业务写入，然后执行打包备份
sudo tar -zcvf /data/es-lab-backup-$(date +%F).tar.gz /data/es-lab
```

### 步骤 2：修改配置与 Docker Compose
1.  停止并清理当前的旧容器环境（这不会删除 `/data` 下的数据卷）：
    ```bash
    cd /opt/es-lab
    docker compose down
    ```
2.  修改 `/opt/es-lab/docker-compose.yml`，采用专职分设的拓扑配置（可直接参考新编写的 `docker-compose.yml`）。
    *   **关键点**：`es-data-01` 挂载 `/data/es-lab/es01/data`；`es-data-02` 挂载 `/data/es-lab/es02/data`；`es-data-03` 挂载 `/data/es-lab/es03/data`。这保证了数据被完美继承。

### 步骤 3：启动新集群并监控日志
运行新编排，并观察 Master 节点的选举和 Data 节点的加入过程：
```bash
docker compose up -d
# 监控 Master 节点日志，确认选举成功
docker logs -f es-master-01
```

### 步骤 4：验证数据与集群健康度
1.  等待大约 30 秒，使用命令行或 Kibana 控制台验证集群健康度：
    ```bash
    curl -u elastic:elastic123 -k -X GET "https://localhost:9200/_cluster/health?pretty"
    ```
    *   期待状态：`status` 应为 `green` 或 `yellow`（分片分配中可能短暂呈现 yellow，待全部分配完成后恢复 green）。
2.  查看节点角色分布情况：
    ```bash
    curl -u elastic:elastic123 -k -X GET "https://localhost:9200/_cat/nodes?v"
    ```
    *   期待输出中，3 个 `es-master` 节点的 `node.role` 为 `m`（Master），3 个 `es-data` 节点的 `node.role` 为 `di`（Data/Ingest）。带 `*` 号的代表当前被选举出的活跃主节点。
3.  检查已有向量索引是否完好：
    ```bash
    curl -u elastic:elastic123 -k -X GET "https://localhost:9200/_cat/indices?v"
    ```
    *   确认你原有的 `practice_knowledge_chunks` 索引依然存在且状态正常。

---

## 四、 升级过程常见问题

*   **问题：集群健康状态呈现 Red**
    *   **原因**：可能是某个数据节点的目录权限有问题，导致分片未能成功加载。
    *   **解决**：重新对数据目录授权 `sudo chmod -R 777 /data/es-lab`，然后重启数据节点。
*   **问题：新 Master 节点无法选主**
    *   **原因**：`cluster.initial_master_nodes` 未正确配置为 3 个 Master 节点的名字，或者 `discovery.seed_hosts` 未配对。
    *   **解决**：检查 `docker-compose.yml` 中这三个 Master 节点的网络互通性与种子配置。
