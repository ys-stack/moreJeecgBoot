## JeecgBoot 项目启动指南

本项目为前后端分离架构，需要分别启动后端（Java）和前端（Vue3）。

---

### 一、环境总览

| 项目 | 要求 | 你本地情况 | 状态 |
|------|------|-----------|------|
| JDK | 17+ | D:\soft\java\jdk17 (17.0.12) | OK |
| Maven | 3.6.3+ | 3.3.9（**版本过低，需升级**） | 需处理 |
| MySQL/MariaDB | 5.7+ | MariaDB 10.12（端口3307） | OK，需改配置 |
| Redis | 5.0+ | Redis 3.2（已配服务） | 可用 |
| Node.js | 20+ | v24.14.1 | OK |
| pnpm | 9+ | 11.5.2（刚装好） | OK |

---

### 二、需要修改的配置文件（共2处）

#### 修改1：后端数据库连接（必改）

**文件路径：** `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml`

找到 `datasource.master` 部分（约第135行），修改3处：

```yaml
# 修改前：
datasource:
  master:
    url: jdbc:mysql://127.0.0.1:3306/jeecg-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&tinyInt1isBit=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver

# 修改后（端口改为3307，密码改为你MariaDB的root密码）：
datasource:
  master:
    url: jdbc:mysql://127.0.0.1:3307/jeecg-boot?characterEncoding=UTF-8&useUnicode=true&useSSL=false&tinyInt1isBit=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
    username: root
    password: 你的MariaDB密码
    driver-class-name: com.mysql.cj.jdbc.Driver
```

**说明：** MariaDB 10.12 完全兼容 MySQL 8.0 协议，用 `com.mysql.cj.jdbc.Driver` 即可，无需换驱动。

#### 修改2：前端代理配置（无需改，确认即可）

**文件路径：** `jeecgboot-vue3/.env.development`

```
VITE_PROXY = [["/jeecgboot","http://localhost:8080/jeecg-boot"],["/upload","http://localhost:3300/upload"]]
VITE_GLOB_DOMAIN_URL=http://localhost:8080/jeecg-boot
```

这两行已经正确指向后端 `localhost:8080`，**不需要修改**。

---

### 三、Maven 升级（必须）

当前 Maven 3.3.9 太旧，Spring Boot 3.5.5 要求 Maven 3.6.3+。

**方式一：手动下载**
1. 访问 https://maven.apache.org/download.cgi
2. 下载 `apache-maven-3.9.x-bin.zip`
3. 解压到 `D:\soft\` 下（如 `D:\soft\apache-maven-3.9.9`）
4. 修改系统环境变量 `MAVEN_HOME` 指向新目录
5. 将 `%MAVEN_HOME%\bin` 加入 `PATH`

**方式二：IDEA 自带**
IDEA 2025.1 自带 Maven 3.9+，在 IDEA 中打开项目时会自动使用内置 Maven，无需额外升级。

---

### 四、后端启动步骤

#### 第1步：启动 MariaDB 和 Redis

确保你的 MariaDB（端口3307）和 Redis（端口6379）服务已启动。

#### 第2步：创建数据库并导入数据

用 Navicat 或命令行连接 MariaDB（127.0.0.1:3307），执行：

```sql
CREATE DATABASE `jeecg-boot` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

然后导入初始化脚本：`jeecg-boot/db/jeecgboot-mysql-5.7.sql`

用 Navicat 操作最方便：右键数据库 → 运行SQL文件 → 选择该 .sql 文件。

#### 第3步：修改配置文件

按上面"修改1"修改 `application-dev.yml` 中的数据库端口和密码。

#### 第4步：用 IDEA 启动后端

1. IDEA 中打开 `jeecg-boot` 目录
2. 确认 Project SDK 设为 JDK 17（File → Project Structure → SDKs）
3. 确认已安装 Lombok 插件（Settings → Plugins → 搜索 Lombok）
4. 等待 Maven 下载依赖完成（右下角进度条）
5. 找到启动类：`jeecg-module-system/jeecg-system-start/src/main/java/org/jeecg/JeecgSystemApplication.java`
6. 右键 → Run 或 Debug 启动

启动成功后控制台会显示：
```
Started JeecgSystemApplication in xx seconds
```

后端地址：http://localhost:8080/jeecg-boot

**验证：** 浏览器访问 http://localhost:8080/jeecg-boot/doc.html 能看到 Swagger 接口文档即成功。

---

### 五、前端启动步骤

#### 第1步：安装依赖（已自动完成）

```bash
cd E:\workspace\gitSpace\moreJeecgBoot\jeecgboot-vue3
pnpm install
```

#### 第2步：启动开发服务器

```bash
pnpm dev
```

启动后访问 http://localhost:3100

#### 第3步：登录

默认账号：`admin`，默认密码：`123456`

---

### 六、常见问题

**Q: 后端启动报"Access denied"数据库错误？**
检查 application-dev.yml 中 MariaDB 的密码是否正确，确认数据库 `jeecg-boot` 已创建。

**Q: 后端启动报 Redis 连接失败？**
确认 Redis 服务已启动（端口6379，无密码）。

**Q: 前端页面空白或接口404？**
确认后端已启动并运行在8080端口，前端代理配置指向正确。

**Q: Maven下载依赖很慢？**
建议配置阿里云镜像。在 Maven 的 `settings.xml` 中 `<mirrors>` 下添加：
```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <name>Aliyun Maven</name>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

**Q: AI功能不可用？**
AI聊天、绘图等功能需要配置 DeepSeek / 阿里通义 的 API Key，在 `application-dev.yml` 中搜索 `apiKey` 填入你的密钥。不影响基础功能使用。

---

### 七、快速检查清单

- [ ] MariaDB 已启动（端口3307）
- [ ] 已创建 `jeecg-boot` 数据库
- [ ] 已导入 `jeecgboot-mysql-5.7.sql`
- [ ] Redis 已启动（端口6379）
- [ ] `application-dev.yml` 已修改端口和密码
- [ ] IDEA 使用 JDK 17
- [ ] IDEA 已安装 Lombok 插件
- [ ] Maven 版本 >= 3.6.3（或使用 IDEA 内置）
- [ ] 后端启动成功（8080端口）
- [ ] 前端 `pnpm install` 完成
- [ ] 前端 `pnpm dev` 启动（3100端口）
- [ ] 浏览器访问 http://localhost:3100 用 admin/123456 登录
