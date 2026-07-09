# Elasticsearch 真实生产环境部署实操文档（3 Master + 3 Data + 2 Coordinating）

> 这份文档不再模拟“单台虚拟机里跑 3 个 master/data 混合节点”，而是按更接近真实生产的方式设计：**3 个 dedicated master 节点 + 3 个 dedicated data 节点 + 2 个 coordinating-only 路由节点**。业务服务只访问路由节点或负载均衡地址，master 不承担读写流量，data 专注存储、索引、检索和向量搜索。
>
> 版本示例仍使用 `docker.elastic.co/elasticsearch/elasticsearch:8.17.0`，因为你本地已经按这个版本练习过。真实公司环境应统一使用团队认证过的 ES 版本，所有节点版本必须一致。

---

## 目录

- [一、生产拓扑设计](#一生产拓扑设计)
- [二、服务器规划](#二服务器规划)
- [三、网络与端口规划](#三网络与端口规划)
- [四、系统参数准备](#四系统参数准备)
- [五、证书与安全策略](#五证书与安全策略)
- [六、节点配置模板](#六节点配置模板)
- [七、按角色启动节点](#七按角色启动节点)
- [八、负载均衡与业务连接](#八负载均衡与业务连接)
- [九、验证集群状态](#九验证集群状态)
- [十、索引模板、分片和别名](#十索引模板分片和别名)
- [十一、RAG 向量索引方案](#十一rag-向量索引方案)
- [十二、备份、监控和日志](#十二备份监控和日志)
- [十三、故障演练](#十三故障演练)
- [十四、生产禁忌清单](#十四生产禁忌清单)
- [十五、面试和项目答辩话术](#十五面试和项目答辩话术)

---

## 一、生产拓扑设计

### 1.1 推荐拓扑

```text
                 Java / JeecgBoot / RAG 服务
                           |
                    ES 负载均衡地址
                 https://es-search.example.com:9200
                           |
          +----------------+----------------+
          |                                 |
  es-coord-01                         es-coord-02
  node.roles: []                      node.roles: []
  只做请求路由、聚合、结果合并              只做请求路由、聚合、结果合并
          |                                 |
          +--------------+------------------+
                         |
    +--------------------+--------------------+
    |                    |                    |
es-data-01          es-data-02          es-data-03
node.roles:          node.roles:          node.roles:
[data,ingest]        [data,ingest]        [data,ingest]
存分片、建索引、检索    存分片、建索引、检索    存分片、建索引、检索
    |                    |                    |
    +--------------------+--------------------+
                         |
    +--------------------+--------------------+
    |                    |                    |
es-master-01        es-master-02        es-master-03
node.roles:          node.roles:          node.roles:
[master]             [master]             [master]
选主、元数据、分片分配  选主、元数据、分片分配  选主、元数据、分片分配
```

### 1.2 为什么要拆角色

| 角色 | 是否存数据 | 是否处理业务查询 | 主要职责 |
| --- | --- | --- | --- |
| master | 否 | 否 | 选主、维护集群元数据、分片分配、节点加入退出 |
| data | 是 | 间接处理 | 保存 primary/replica shard，执行索引写入、搜索、聚合、向量 kNN |
| coordinating-only | 否 | 是 | 接收业务请求，转发到 data 节点，合并结果返回 |

真实生产里，最怕的是 master 被查询、聚合、向量检索打爆。master 一旦不稳定，整个集群的分片分配、节点发现和元数据更新都会受影响。所以：

1. **master 节点只做 master**，不让业务服务访问。
2. **data 节点只做数据和计算**，主要承载索引、搜索、向量检索。
3. **业务服务只连 coordinating 节点或 LB**，不直连 master。
4. **3 个 master 是最小高可用数量**，可以容忍 1 个 master 宕机，仍然保留多数派选主能力。

### 1.3 是否必须加 coordinating 节点

如果只是中小规模集群，6 台机器也能跑：3 master + 3 data，业务通过 LB 连 3 个 data 节点。但更真实的生产方案建议加 **2 个 coordinating-only 节点**。

加 coordinating 节点的价值：

1. 业务连接点稳定，后端 data 节点扩缩容时业务无感。
2. 大查询、聚合、跨分片结果合并不压在 data 节点 HTTP 层。
3. Kibana、管理脚本、Java 服务都走同一组入口，访问控制更清晰。
4. 两个 coordinating 节点可以做高可用，任意一个挂掉业务还能访问。

本方案按 **8 台 ES 服务器** 设计，另外建议准备 1 台运维/监控服务器放 Kibana、Nginx/HAProxy、Prometheus 或备份脚本。

---

## 二、服务器规划

### 2.1 生产推荐规格

| 主机名 | IP 示例 | 节点角色 | CPU | 内存 | JVM Heap | 磁盘 | 说明 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| es-master-01 | 10.10.10.11 | master | 2C | 4G | 1G | 50G SSD | dedicated master |
| es-master-02 | 10.10.10.12 | master | 2C | 4G | 1G | 50G SSD | dedicated master |
| es-master-03 | 10.10.10.13 | master | 2C | 4G | 1G | 50G SSD | dedicated master |
| es-data-01 | 10.10.10.21 | data,ingest | 8C | 32G | 16G | 500G+ SSD | 存储和检索 |
| es-data-02 | 10.10.10.22 | data,ingest | 8C | 32G | 16G | 500G+ SSD | 存储和检索 |
| es-data-03 | 10.10.10.23 | data,ingest | 8C | 32G | 16G | 500G+ SSD | 存储和检索 |
| es-coord-01 | 10.10.10.31 | coordinating-only | 4C | 8G | 2G | 50G SSD | 业务入口 |
| es-coord-02 | 10.10.10.32 | coordinating-only | 4C | 8G | 2G | 50G SSD | 业务入口 |
| es-ops-01 | 10.10.10.41 | Kibana/LB/脚本 | 2C | 4G | - | 100G | 非 ES 节点 |

> JVM Heap 不要无脑给满内存。data 节点要给 Lucene 文件系统缓存、向量索引、segment merge 和 OS 留足堆外空间。常见原则是 Heap 不超过机器内存 50%，并且尽量不要超过 31G。

### 2.2 练习环境最低规格

如果你是本地 VMware 多台虚拟机练习，可以降配：

| 角色 | 台数 | 最低配置 |
| --- | --- | --- |
| master | 3 | 1C / 2G / Heap 512M |
| data | 3 | 2C / 4G / Heap 2G |
| coordinating | 2 | 1C / 2G / Heap 512M-1G |

注意：这只是为了把拓扑跑通。真实生产不要用这么低的配置跑向量检索。

---

## 三、网络与端口规划

| 端口 | 开放对象 | 用途 | 生产建议 |
| --- | --- | --- | --- |
| 9200 | Java 服务、Kibana、运维网段访问 coordinating 节点 | HTTP API | 不对公网开放 |
| 9300 | ES 节点之间互通 | transport 通信、选主、数据传输 | 只允许 8 个 ES 节点互通 |
| 5601 | 运维网段访问 es-ops-01 | Kibana | 不对公网开放 |

防火墙原则：

1. Java 服务只能访问 `es-coord-01/02:9200` 或负载均衡地址。
2. Java 服务不能访问 `es-master-*:9200`。
3. master、data、coord 节点之间必须互通 9300。
4. 9200 必须启用账号密码，生产建议启用 HTTPS。

示例防火墙规则思路：

```bash
# 每台 ES 节点允许集群内部 transport 通信
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="10.10.10.0/24" port protocol="tcp" port="9300" accept'

# 只有 coordinating 节点开放 9200 给业务网段
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="10.10.20.0/24" port protocol="tcp" port="9200" accept'

sudo firewall-cmd --reload
```

---

## 四、系统参数准备

以下操作在 8 台 ES 节点上都要做。

### 4.1 关闭 swap

```bash
sudo swapoff -a
sudo sed -i.bak '/ swap / s/^/#/' /etc/fstab
free -h
```

### 4.2 vm.max_map_count

```bash
sudo tee /etc/sysctl.d/99-elasticsearch.conf > /dev/null <<'EOF'
vm.max_map_count=262144
fs.file-max=1048576
EOF

sudo sysctl --system
sysctl vm.max_map_count
```

### 4.3 文件句柄和进程数

```bash
sudo tee /etc/security/limits.d/99-elasticsearch.conf > /dev/null <<'EOF'
elasticsearch soft nofile 65535
elasticsearch hard nofile 65535
elasticsearch soft nproc 4096
elasticsearch hard nproc 4096
elasticsearch soft memlock unlimited
elasticsearch hard memlock unlimited
EOF
```

如果用 Docker Compose，每个服务还要配：

```yaml
ulimits:
  memlock:
    soft: -1
    hard: -1
  nofile:
    soft: 65535
    hard: 65535
```

### 4.4 数据目录

每台节点单独准备本机目录，不能多个节点共享同一个数据目录。

```bash
sudo mkdir -p /data/elasticsearch/data
sudo mkdir -p /data/elasticsearch/logs
sudo mkdir -p /data/elasticsearch/certs
sudo chown -R 1000:1000 /data/elasticsearch
sudo chmod -R 750 /data/elasticsearch
```

本地练习为了省事可以 `chmod 777`，但生产不能这么做。

---

## 五、证书与安全策略

生产环境必须开启：

1. `xpack.security.enabled=true`
2. transport 层 TLS，用于节点间通信。
3. HTTP 层 TLS，用于业务访问 ES API。
4. 内置用户或专用业务用户，不要让业务用 `elastic` 超级用户。

### 5.1 生成证书配置

在 `es-ops-01` 上准备 `instances.yml`：

```yaml
instances:
  - name: es-master-01
    dns: ["es-master-01"]
    ip: ["10.10.10.11"]
  - name: es-master-02
    dns: ["es-master-02"]
    ip: ["10.10.10.12"]
  - name: es-master-03
    dns: ["es-master-03"]
    ip: ["10.10.10.13"]
  - name: es-data-01
    dns: ["es-data-01"]
    ip: ["10.10.10.21"]
  - name: es-data-02
    dns: ["es-data-02"]
    ip: ["10.10.10.22"]
  - name: es-data-03
    dns: ["es-data-03"]
    ip: ["10.10.10.23"]
  - name: es-coord-01
    dns: ["es-coord-01"]
    ip: ["10.10.10.31"]
  - name: es-coord-02
    dns: ["es-coord-02"]
    ip: ["10.10.10.32"]
```

生成 CA 和节点证书：

```bash
mkdir -p /opt/es-certs
cd /opt/es-certs

# 生成 CA
docker run --rm -v $(pwd):/certs docker.elastic.co/elasticsearch/elasticsearch:8.17.0 \
  bash -c "elasticsearch-certutil ca --silent --pem -out /certs/ca.zip"

unzip ca.zip

# 生成每个节点的证书
docker run --rm -v $(pwd):/certs docker.elastic.co/elasticsearch/elasticsearch:8.17.0 \
  bash -c "elasticsearch-certutil cert --silent --pem --ca-cert /certs/ca/ca.crt --ca-key /certs/ca/ca.key --in /certs/instances.yml -out /certs/certs.zip"

unzip certs.zip
```

把对应节点证书分发到各节点：

```bash
scp -r ca es-master-01 es-master-01:/data/elasticsearch/certs/
scp -r ca es-master-02 es-master-02:/data/elasticsearch/certs/
scp -r ca es-master-03 es-master-03:/data/elasticsearch/certs/
scp -r ca es-data-01 es-data-01:/data/elasticsearch/certs/
scp -r ca es-data-02 es-data-02:/data/elasticsearch/certs/
scp -r ca es-data-03 es-data-03:/data/elasticsearch/certs/
scp -r ca es-coord-01 es-coord-01:/data/elasticsearch/certs/
scp -r ca es-coord-02 es-coord-02:/data/elasticsearch/certs/
```

每台机器上修权限：

```bash
sudo chown -R 1000:1000 /data/elasticsearch/certs
sudo chmod -R 750 /data/elasticsearch/certs
```

---

## 六、节点配置模板

生产上建议每台服务器一个 ES 进程或一个 ES 容器，不建议一台机器跑多个生产节点。下面用 Docker Compose 表达，真实公司也可以改成 systemd + rpm/tar 包部署。

### 6.1 通用 `.env`

每台 ES 服务器创建 `/opt/elasticsearch/.env`：

```bash
STACK_VERSION=8.17.0
CLUSTER_NAME=jeecg-ai-es-prod
ELASTIC_PASSWORD=请换成强密码
```

各角色 Heap 单独设置：

```bash
# master 节点
ES_JAVA_OPTS=-Xms1g -Xmx1g

# data 节点
ES_JAVA_OPTS=-Xms16g -Xmx16g

# coordinating 节点
ES_JAVA_OPTS=-Xms2g -Xmx2g
```

### 6.2 master 节点 compose 模板

以 `es-master-01` 为例，`/opt/elasticsearch/docker-compose.yml`：

```yaml
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:${STACK_VERSION}
    container_name: es-master-01
    hostname: es-master-01
    restart: always
    environment:
      - cluster.name=${CLUSTER_NAME}
      - node.name=es-master-01
      - node.roles=master
      - network.host=0.0.0.0
      - discovery.seed_hosts=es-master-01,es-master-02,es-master-03
      - cluster.initial_master_nodes=es-master-01,es-master-02,es-master-03
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=${ES_JAVA_OPTS}
      - ELASTIC_PASSWORD=${ELASTIC_PASSWORD}
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
    ports:
      - "9200:9200"
      - "9300:9300"
    ulimits:
      memlock:
        soft: -1
        hard: -1
      nofile:
        soft: 65535
        hard: 65535
    healthcheck:
      test: ["CMD-SHELL", "curl -k -u elastic:${ELASTIC_PASSWORD} https://localhost:9200/_cluster/health || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5
```

`es-master-02`、`es-master-03` 只改：

1. `container_name`
2. `hostname`
3. `node.name`
4. 证书路径里的节点名

> 重要：`cluster.initial_master_nodes` 只在第一次创建新集群时使用。集群首次启动成功后，生产配置里应移除这一项，避免误引导出新集群。

### 6.3 data 节点 compose 模板

以 `es-data-01` 为例：

```yaml
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:${STACK_VERSION}
    container_name: es-data-01
    hostname: es-data-01
    restart: always
    environment:
      - cluster.name=${CLUSTER_NAME}
      - node.name=es-data-01
      - node.roles=data,ingest
      - network.host=0.0.0.0
      - discovery.seed_hosts=es-master-01,es-master-02,es-master-03
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=${ES_JAVA_OPTS}
      - ELASTIC_PASSWORD=${ELASTIC_PASSWORD}
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
    ports:
      - "9200:9200"
      - "9300:9300"
    ulimits:
      memlock:
        soft: -1
        hard: -1
      nofile:
        soft: 65535
        hard: 65535
```

如果你后续要做冷热分层，可以把 data 节点拆成：

```yaml
node.roles=data_hot,ingest
```

或者：

```yaml
node.roles=data_content,ingest
```

当前 RAG/业务搜索阶段先用 `data,ingest` 更简单，面试也更容易讲清楚。

### 6.4 coordinating-only 节点 compose 模板

以 `es-coord-01` 为例：

```yaml
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:${STACK_VERSION}
    container_name: es-coord-01
    hostname: es-coord-01
    restart: always
    environment:
      - cluster.name=${CLUSTER_NAME}
      - node.name=es-coord-01
      - node.roles=[]
      - network.host=0.0.0.0
      - discovery.seed_hosts=es-master-01,es-master-02,es-master-03
      - bootstrap.memory_lock=true
      - ES_JAVA_OPTS=${ES_JAVA_OPTS}
      - ELASTIC_PASSWORD=${ELASTIC_PASSWORD}
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
    ports:
      - "9200:9200"
      - "9300:9300"
    ulimits:
      memlock:
        soft: -1
        hard: -1
      nofile:
        soft: 65535
        hard: 65535
```

`node.roles=[]` 表示不具备 master/data/ingest 等专用角色，只作为协调节点参与请求转发和结果合并。

---

## 七、按角色启动节点

启动顺序建议：

```text
先启动 3 个 master
  -> 确认选主成功
  -> 启动 3 个 data
  -> 确认 data 加入
  -> 启动 2 个 coordinating
  -> 配置负载均衡
  -> 创建业务用户、索引模板和索引
```

### 7.1 启动 master

在三台 master 上分别执行：

```bash
cd /opt/elasticsearch
docker compose up -d
```

查看日志：

```bash
docker logs -f es-master-01
```

应该能看到类似信息：

```text
master node changed
cluster UUID set
```

### 7.2 移除 initial_master_nodes

首次集群形成后，在 3 个 master 的 compose 配置中移除：

```yaml
- cluster.initial_master_nodes=es-master-01,es-master-02,es-master-03
```

然后滚动重启 master，每次只重启一个：

```bash
docker compose restart elasticsearch
```

等集群恢复 green 后再重启下一个。

### 7.3 启动 data 和 coordinating

在 data、coord 节点分别执行：

```bash
cd /opt/elasticsearch
docker compose up -d
```

---

## 八、负载均衡与业务连接

### 8.1 Nginx 负载均衡示例

在 `es-ops-01` 上部署 Nginx 或使用公司 SLB。Nginx 示例：

```nginx
upstream es_coord_backend {
    server 10.10.10.31:9200 max_fails=3 fail_timeout=10s;
    server 10.10.10.32:9200 max_fails=3 fail_timeout=10s;
}

server {
    listen 9200 ssl;
    server_name es-search.example.com;

    ssl_certificate     /etc/nginx/certs/es-search.crt;
    ssl_certificate_key /etc/nginx/certs/es-search.key;

    location / {
        proxy_pass https://es_coord_backend;
        proxy_ssl_verify off;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_connect_timeout 3s;
        proxy_read_timeout 60s;
    }
}
```

生产更推荐使用云厂商内网 SLB 或专业四层/七层负载均衡，并配置健康检查。

### 8.2 JeecgBoot / Java 服务连接方式

业务侧只配置负载均衡地址：

```yaml
practice:
  vector:
    es:
      uris:
        - https://es-search.example.com:9200
      username: jeecg_rag
      password: ${ES_RAG_PASSWORD}
```

不要配置 master：

```yaml
# 禁止：业务不连 master
# https://es-master-01:9200
# https://es-master-02:9200
# https://es-master-03:9200
```

### 8.3 创建最小权限业务用户

不要让业务服务用 `elastic`。创建角色和用户：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X POST "https://es-search.example.com:9200/_security/role/jeecg_rag_role" \
  -H 'Content-Type: application/json' \
  -d '{
    "cluster": ["monitor"],
    "indices": [
      {
        "names": ["knowledge_chunks*", "product_search*"],
        "privileges": ["read", "view_index_metadata", "create_index", "write", "manage"]
      }
    ]
  }'

curl -k -u elastic:${ELASTIC_PASSWORD} -X POST "https://es-search.example.com:9200/_security/user/jeecg_rag" \
  -H 'Content-Type: application/json' \
  -d '{
    "password": "请换成强密码",
    "roles": ["jeecg_rag_role"],
    "full_name": "JeecgBoot RAG Service User"
  }'
```

权限可以再收紧：生产里创建索引、模板、查询、写入最好拆成不同账号。

---

## 九、验证集群状态

### 9.1 查看健康状态

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cluster/health?pretty"
```

期望：

```json
{
  "cluster_name": "jeecg-ai-es-prod",
  "status": "green",
  "number_of_nodes": 8,
  "number_of_data_nodes": 3
}
```

### 9.2 查看节点角色

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/nodes?v&h=name,ip,roles,master,heap.percent,ram.percent,cpu,node.role"
```

你应该看到：

```text
es-master-01  10.10.10.11  m   *
es-master-02  10.10.10.12  m   -
es-master-03  10.10.10.13  m   -
es-data-01    10.10.10.21  di  -
es-data-02    10.10.10.22  di  -
es-data-03    10.10.10.23  di  -
es-coord-01   10.10.10.31  -   -
es-coord-02   10.10.10.32  -   -
```

`roles` 里：

| 标记 | 含义 |
| --- | --- |
| `m` | master-eligible |
| `d` | data |
| `i` | ingest |
| `-` | coordinating-only |

### 9.3 查看分片分布

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/shards?v"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/allocation?v"
```

分片应该只落在 `es-data-*`，不应该落在 master 或 coord。

---

## 十、索引模板、分片和别名

### 10.1 分片原则

3 个 data 节点下，业务初期推荐：

| 场景 | primary shards | replicas | 说明 |
| --- | --- | --- | --- |
| 商品/订单搜索 | 3 | 1 | 分散到 3 个 data，容忍 1 台 data 宕机 |
| RAG chunk 少于百万 | 3 | 1 | 足够演练生产拓扑 |
| 小索引/配置索引 | 1 | 1 | 避免过度分片 |
| 日志类时间索引 | 按日/按月评估 | 1 | 配 ILM 或定期归档 |

分片不是越多越好。分片太多会增加集群状态、文件句柄、查询合并和恢复成本。

### 10.2 商品索引模板

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X PUT "https://es-search.example.com:9200/_index_template/product_search_template" \
  -H 'Content-Type: application/json' \
  -d '{
    "index_patterns": ["product_search_v*"],
    "template": {
      "settings": {
        "number_of_shards": 3,
        "number_of_replicas": 1,
        "refresh_interval": "1s"
      },
      "mappings": {
        "properties": {
          "id": { "type": "keyword" },
          "title": { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
          "brand": { "type": "keyword" },
          "categoryId": { "type": "keyword" },
          "status": { "type": "keyword" },
          "price": { "type": "integer" },
          "createdAt": { "type": "date" }
        }
      }
    }
  }'
```

创建版本索引和别名：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X PUT "https://es-search.example.com:9200/product_search_v1"

curl -k -u elastic:${ELASTIC_PASSWORD} -X POST "https://es-search.example.com:9200/_aliases" \
  -H 'Content-Type: application/json' \
  -d '{
    "actions": [
      { "add": { "index": "product_search_v1", "alias": "product_search_read" } },
      { "add": { "index": "product_search_v1", "alias": "product_search_write", "is_write_index": true } }
    ]
  }'
```

业务读写都走 alias，不写死 `product_search_v1`。

---

## 十一、RAG 向量索引方案

### 11.1 RAG chunk 索引

以 bge-m3 1024 维向量为例：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X PUT "https://es-search.example.com:9200/knowledge_chunks_v1" \
  -H 'Content-Type: application/json' \
  -d '{
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1,
      "refresh_interval": "5s"
    },
    "mappings": {
      "properties": {
        "id": { "type": "keyword" },
        "knowledgeBaseId": { "type": "keyword" },
        "documentId": { "type": "keyword" },
        "tenantId": { "type": "keyword" },
        "ownerUserId": { "type": "keyword" },
        "accessRoleIds": { "type": "keyword" },
        "sourceFileName": { "type": "keyword" },
        "chunkText": { "type": "text" },
        "chunkOrder": { "type": "integer" },
        "createTime": { "type": "date" },
        "chunkVector": {
          "type": "dense_vector",
          "dims": 1024,
          "index": true,
          "similarity": "cosine"
        }
      }
    }
  }'
```

创建别名：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X POST "https://es-search.example.com:9200/_aliases" \
  -H 'Content-Type: application/json' \
  -d '{
    "actions": [
      { "add": { "index": "knowledge_chunks_v1", "alias": "knowledge_chunks_read" } },
      { "add": { "index": "knowledge_chunks_v1", "alias": "knowledge_chunks_write", "is_write_index": true } }
    ]
  }'
```

### 11.2 RAG 查询必须带权限过滤

生产 RAG 查询不能只做向量相似度，必须把租户、知识库、角色权限一起带进 filter：

```json
{
  "knn": {
    "field": "chunkVector",
    "query_vector": [0.012, -0.034],
    "k": 5,
    "num_candidates": 50,
    "filter": {
      "bool": {
        "filter": [
          { "term": { "tenantId": "tenant_001" } },
          { "term": { "knowledgeBaseId": "kb_001" } },
          { "terms": { "accessRoleIds": ["role_admin", "role_rag_user"] } }
        ]
      }
    }
  }
}
```

如果当前用户没有任何可访问知识库，后端应该直接拒绝或返回空结果，禁止降级成“搜索全部知识库”。

### 11.3 向量检索容量注意点

1. HNSW 向量索引会占用明显的堆外和文件系统缓存。
2. `num_candidates` 越大，召回越稳，但延迟越高。
3. RAG 查询建议先记录 `topK`、`num_candidates`、耗时、召回命中文档和 token 成本。
4. 向量索引不要频繁更新单条文档，大批量导入时可以临时调大 `refresh_interval`。
5. 如果 chunk 数量很小，不要为了“看起来生产”把 shard 开太多。

---

## 十二、备份、监控和日志

### 12.1 快照仓库

生产不要只依赖副本。副本解决节点故障，快照解决误删、误改、集群级故障和灾备。

建议使用对象存储或 NFS 快照仓库。示例：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X PUT "https://es-search.example.com:9200/_snapshot/es_prod_backup" \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "fs",
    "settings": {
      "location": "/mnt/es_snapshots",
      "compress": true
    }
  }'
```

创建快照：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X PUT "https://es-search.example.com:9200/_snapshot/es_prod_backup/snapshot_20260709?wait_for_completion=true"
```

### 12.2 监控指标

必须看这些指标：

| 类别 | 指标 |
| --- | --- |
| 集群 | health、节点数、master 是否稳定、pending tasks |
| 分片 | unassigned shards、relocating shards、shard size |
| JVM | heap used、GC 次数和耗时、old GC |
| 查询 | search latency、fetch latency、slowlog |
| 写入 | indexing rate、refresh、merge、flush |
| 磁盘 | disk used、watermark、IO wait |
| 线程池 | search/write queue、rejected |
| RAG | kNN 耗时、num_candidates、topK 命中率、拒答率 |

常用排查命令：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/health?v"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/nodes?v"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/shards?v"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cluster/pending_tasks?pretty"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cluster/allocation/explain?pretty"
```

### 12.3 慢查询日志

对关键索引开启慢查询日志：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} -X PUT "https://es-search.example.com:9200/knowledge_chunks_v1/_settings" \
  -H 'Content-Type: application/json' \
  -d '{
    "index.search.slowlog.threshold.query.warn": "2s",
    "index.search.slowlog.threshold.query.info": "1s",
    "index.search.slowlog.threshold.fetch.warn": "1s",
    "index.indexing.slowlog.threshold.index.warn": "1s"
  }'
```

---

## 十三、故障演练

### 13.1 停一个 master

```bash
ssh es-master-01 "cd /opt/elasticsearch && docker compose stop elasticsearch"
```

预期：

1. 集群仍可用。
2. 3 个 master 少 1 个，剩余 2 个仍满足多数派。
3. `_cat/nodes` 里 `*` 会落到另一个 master。
4. 查询和写入不应该受明显影响。

验证：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/nodes?v&h=name,roles,master"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cluster/health?pretty"
```

### 13.2 停一个 data

```bash
ssh es-data-02 "cd /opt/elasticsearch && docker compose stop elasticsearch"
```

预期：

1. 集群可能短暂 yellow，分片重分配后恢复 green。
2. 只要 `number_of_replicas=1` 且剩余 data 磁盘够用，单 data 故障可恢复。
3. 查询会有一定抖动，业务应有超时和重试策略。

验证：

```bash
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/shards?v"
curl -k -u elastic:${ELASTIC_PASSWORD} "https://es-search.example.com:9200/_cat/recovery?v"
```

### 13.3 停一个 coordinating

```bash
ssh es-coord-01 "cd /opt/elasticsearch && docker compose stop elasticsearch"
```

预期：

1. LB 健康检查摘除 `es-coord-01`。
2. 业务请求切到 `es-coord-02`。
3. master/data 不受影响。

### 13.4 不要做的故障演练

不要在生产直接同时停两个 master。3 master 只能容忍 1 个 master 故障，同时丢 2 个 master 会失去多数派，集群无法完成选主。

---

## 十四、生产禁忌清单

1. 不要让业务服务连接 master 节点。
2. 不要把 3 master + 3 data 全跑在同一台物理机上冒充生产。
3. 不要多个 ES 节点共享同一个 data 目录。
4. 不要关闭安全认证暴露 9200。
5. 不要让业务使用 `elastic` 超级用户。
6. 不要长期保留 `cluster.initial_master_nodes`。
7. 不要一开始就建几十个分片。
8. 不要忽略磁盘水位，磁盘高水位会导致分片无法分配。
9. 不要只配副本不做快照。
10. 不要在没有评测数据的情况下盲目调大 `topK` 和 `num_candidates`。
11. 不要把 RAG 权限只放在 Java 层，ES filter 也必须带租户和权限条件。
12. 不要把 master、data、coord 的 JVM Heap 配成一样。

---

## 十五、面试和项目答辩话术

### 15.1 怎么介绍这套 ES 生产架构

> 我把 ES 集群按生产角色拆成 3 个 dedicated master、3 个 data 和 2 个 coordinating-only 节点。master 只负责选主、集群元数据和分片分配，不承担读写流量；data 节点负责索引、搜索、聚合和 RAG 向量检索；业务服务不直连 master/data，而是通过负载均衡访问两个 coordinating 节点。这样可以避免查询和向量检索压力影响 master 稳定性，也方便后续 data 节点扩容。

### 15.2 为什么 master 要 3 个

> ES 的 master 需要多数派选举。3 个 master 可以容忍 1 个 master 宕机，剩下 2 个仍然能选主。如果只有 2 个 master，任意一个挂掉就可能无法满足多数派；如果 4 个 master，多数派是 3，容错能力不如 3 个直观。所以 dedicated master 通常用 3 个。

### 15.3 为什么要 coordinating 节点

> coordinating 节点本身不存数据，也不参与选主。它接收业务请求，把查询分发到相关 data shard，再合并排序结果返回。对 Java 服务来说，它提供了稳定入口；对 data 节点来说，它减少了 HTTP 入口和结果合并压力。生产上通常配两个 coordinating 节点挂在 LB 后面，保证入口高可用。

### 15.4 RAG 向量索引怎么落在这套架构上

> RAG 的 chunk 和 dense_vector 存在 data 节点上，索引设置一般按 data 节点数做 3 个 primary shard、1 个 replica。查询时 Java 服务先把问题向量化，然后通过 coordinating 节点发起 kNN 查询，ES 在 data 节点上执行 HNSW 近似搜索，再由 coordinating 节点合并 topK 结果。生产查询必须带 tenantId、knowledgeBaseId、roleIds 等 filter，防止越权召回。

### 15.5 如果一个节点挂了怎么办

> 一个 master 挂了，剩下两个 master 仍能形成多数派，集群继续可用。一个 data 挂了，primary/replica 会在剩余 data 节点上恢复，期间可能短暂 yellow。一个 coordinating 挂了，LB 会把流量切到另一个 coordinating。真正要警惕的是同时丢两个 master、磁盘水位过高、分片无法分配和 JVM/GC 压力。

### 15.6 这套方案和本地 3 节点 demo 的区别

> 3 节点 demo 通常每个节点都是 master+data，能练 API、分片和副本，但不够生产。真实生产要做角色隔离，master 不承担读写，data 专注存储和检索，业务走 coordinating/LB；同时要开启 TLS、安全用户、快照、监控、慢查询、故障演练和容量规划。这才是从“能跑”到“能上线”的差距。