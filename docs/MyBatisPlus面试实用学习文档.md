# MyBatis-Plus 面试实用学习文档

> 适合 3-5 年 Java 工程师复习 MyBatis-Plus。目标不是只会 `BaseMapper`，而是能讲清它和 MyBatis 的关系、CRUD 封装、Wrapper、分页插件、逻辑删除、乐观锁、自动填充、多租户、插件链、SQL 性能和工程边界。JeecgBoot 项目中 MyBatis-Plus 是持久层核心组件，面试很容易被追问。

## 目录

- [一、MyBatis-Plus 面试主线](#一mybatis-plus-面试主线)
- [二、它和 MyBatis 是什么关系](#二它和-mybatis-是什么关系)
- [三、BaseMapper 与 IService](#三basemapper-与-iservice)
- [四、Wrapper 条件构造器](#四wrapper-条件构造器)
- [五、分页、插件链与 SQL 改写](#五分页插件链与-sql-改写)
- [六、逻辑删除、乐观锁与自动填充](#六逻辑删除乐观锁与自动填充)
- [七、多租户、数据权限与动态表名](#七多租户数据权限与动态表名)
- [八、批量操作与性能优化](#八批量操作与性能优化)
- [九、常见坑点](#九常见坑点)
- [十、面试高频回答模板](#十面试高频回答模板)

---

## 一、MyBatis-Plus 面试主线

```text
MyBatis 执行链
  -> MyBatis-Plus 增强 CRUD
  -> Mapper / Service 封装
  -> Wrapper 动态条件
  -> 插件链拦截 SQL
  -> 分页 / 逻辑删除 / 乐观锁 / 多租户
  -> SQL 性能和边界控制
```

4 年经验回答时要强调：**MyBatis-Plus 是增强工具，不是让我们忽略 SQL 的工具**。

---

## 二、它和 MyBatis 是什么关系

MyBatis-Plus 是 MyBatis 的增强框架，不改变 MyBatis 的核心执行链：

```text
MapperProxy
  -> SqlSession
  -> Executor
  -> StatementHandler
  -> ParameterHandler
  -> ResultSetHandler
```

MyBatis-Plus 主要增强：

1. 通用 CRUD。
2. 条件构造器。
3. 分页插件。
4. 逻辑删除。
5. 乐观锁。
6. 自动填充。
7. 多租户、动态表名等插件能力。
8. 代码生成和元数据映射。

一句话：

> MyBatis-Plus 帮我们减少重复 CRUD 和通用 SQL 拼装，但复杂查询、性能优化、索引设计仍然要回到 SQL 本身。

---

## 三、BaseMapper 与 IService

### 3.1 BaseMapper

```java
public interface UserMapper extends BaseMapper<User> {
}
```

提供常见方法：

| 方法 | 含义 |
| --- | --- |
| `insert` | 插入 |
| `deleteById` | 按 id 删除 |
| `updateById` | 按 id 更新 |
| `selectById` | 按 id 查询 |
| `selectList` | 按条件查询列表 |
| `selectPage` | 分页查询 |

### 3.2 IService

`IService` 在 Mapper 之上提供 Service 层通用能力，例如：

1. `save`
2. `saveBatch`
3. `saveOrUpdate`
4. `removeById`
5. `lambdaQuery`
6. `page`

工程建议：

1. 简单 CRUD 可以用通用方法。
2. 核心业务仍要写清楚 Service 方法名。
3. 不要把 Controller 直接写成“转发 MyBatis-Plus CRUD”，业务规则应在 Service 层沉淀。

---

## 四、Wrapper 条件构造器

### 4.1 QueryWrapper 和 LambdaQueryWrapper

```java
LambdaQueryWrapper<User> wrapper = Wrappers.lambdaQuery(User.class)
    .eq(User::getStatus, "1")
    .like(StringUtils.hasText(keyword), User::getRealname, keyword)
    .orderByDesc(User::getCreateTime);

List<User> users = userMapper.selectList(wrapper);
```

推荐优先使用 Lambda 版本，避免字段名硬编码。

### 4.2 条件参数

Wrapper 很有用的一点是条件开关：

```java
.eq(userId != null, Order::getUserId, userId)
.between(start != null && end != null, Order::getCreateTime, start, end)
```

这样可以减少大量 if 拼接代码。

### 4.3 Wrapper 的边界

Wrapper 适合简单动态条件，不适合所有 SQL。

不适合场景：

1. 多表复杂 join。
2. 窗口函数。
3. 复杂聚合。
4. 复杂权限 SQL。
5. 需要数据库特定优化的查询。

这些场景建议写 XML 或注解 SQL，并配合 Explain 分析。

---

## 五、分页、插件链与 SQL 改写

### 5.1 分页插件做了什么

分页插件会拦截查询 SQL，生成：

```text
count SQL
分页 SQL
```

例如 MySQL：

```sql
select * from sys_user where status = ?
limit ?, ?
```

### 5.2 分页 count 的坑

复杂 SQL 的 count 可能很慢或不准确：

1. 多表 join。
2. group by。
3. distinct。
4. 子查询。
5. 权限条件动态追加。

工程建议：

1. 大表分页必须有合适索引。
2. 深分页避免直接 `limit 100000, 10`。
3. 复杂分页可以单独写 count SQL。
4. 能用游标分页时，不要强依赖页码深跳转。

### 5.3 插件链顺序

MyBatis-Plus 插件本质是 MyBatis 拦截器链。常见能力：

1. 分页。
2. 多租户。
3. 动态表名。
4. 乐观锁。
5. 防全表更新删除。
6. SQL 性能分析。

插件顺序会影响最终 SQL。例如多租户和分页都要改 SQL，就要确保最终 count 和分页 SQL 都带租户条件。

---

## 六、逻辑删除、乐观锁与自动填充

### 6.1 逻辑删除

逻辑删除不是物理删除，而是更新删除标识：

```text
delete from user where id = 1
变成
update user set del_flag = 1 where id = 1
```

查询时自动追加：

```sql
where del_flag = 0
```

注意点：

1. 唯一索引要考虑已删除数据。
2. 历史数据归档和物理清理要有策略。
3. 手写 SQL 要记得带删除标识。
4. 管理员查回收站时要明确绕过逻辑删除。

### 6.2 乐观锁

适合读多写少、冲突较少的场景。

```text
update product
set stock = ?, version = version + 1
where id = ? and version = ?
```

如果更新行数为 0，说明版本冲突，需要重试或提示失败。

常见场景：

1. 商品库存。
2. 配置版本。
3. 表单编辑防覆盖。
4. 审批状态流转。

高并发扣库存仅靠乐观锁可能导致大量重试，要结合缓存、队列、库存预扣、数据库原子 SQL 等方案。

### 6.3 自动填充

常见字段：

1. `createBy`
2. `createTime`
3. `updateBy`
4. `updateTime`
5. `tenantId`

自动填充依赖当前上下文。异步线程里如果拿不到当前用户，需要显式传递上下文或使用系统用户。

---

## 七、多租户、数据权限与动态表名

### 7.1 多租户插件

多租户插件通常会给 SQL 自动追加租户条件：

```sql
select * from sys_user where tenant_id = ?
```

注意：

1. 超级管理员是否绕过。
2. 哪些表不需要租户字段。
3. 手写 SQL、子查询、join 是否正确追加。
4. 缓存 key 是否带租户。
5. 定时任务和异步任务的租户上下文来源。

### 7.2 数据权限

数据权限可以通过 MyBatis 拦截器、AOP 或手写 SQL 实现。核心是给查询追加组织、角色、用户范围条件。

```text
select *
from order
where tenant_id = ?
  and dept_id in (...)
```

数据权限比接口权限更容易漏，尤其是：

1. 导出接口。
2. 详情接口。
3. 批量查询接口。
4. 统计报表。
5. 下拉框数据源。

### 7.3 动态表名

适合分年月表、租户表、日志表等场景。

风险：

1. 表名必须来自白名单或可信规则。
2. SQL 解析复杂度上升。
3. 迁移和归档成本增加。
4. 跨表统计变复杂。

---

## 八、批量操作与性能优化

### 8.1 saveBatch 是不是一定快

不一定。它减少了部分代码量，但性能取决于：

1. JDBC 批处理是否生效。
2. batch size。
3. 数据库索引数量。
4. 网络往返。
5. 事务大小。
6. 唯一键冲突。

大批量导入建议：

1. 分批提交。
2. 先校验再入库。
3. 错误数据单独记录。
4. 避免一个超大事务。
5. 必要时使用数据库导入能力。

### 8.2 N+1 查询

错误示例：

```java
List<Order> orders = orderMapper.selectList(wrapper);
for (Order order : orders) {
    User user = userMapper.selectById(order.getUserId());
}
```

优化：

1. 批量收集 userId。
2. 一次性 `selectBatchIds`。
3. 转成 Map。
4. 回填结果。

### 8.3 只查需要的列

```java
Wrappers.lambdaQuery(User.class)
    .select(User::getId, User::getUsername, User::getRealname);
```

大表、宽表、列表页尤其要避免 `select *`。

---

## 九、常见坑点

### 9.1 update wrapper 导致全表更新

```java
userMapper.update(user, new UpdateWrapper<>());
```

如果没有 where 条件，可能全表更新。生产建议开启防全表更新删除插件，并在代码审查里重点看。

### 9.2 Wrapper 复用

Wrapper 是可变对象，不建议跨请求、跨线程复用。

### 9.3 `last()` 拼接 SQL

```java
wrapper.last("limit 1");
```

`last()` 会直接拼到 SQL 尾部，不做参数化处理。不要拼用户输入，避免注入。

### 9.4 `in` 条件过大

超大 `in` 会导致 SQL 过长、优化器压力大。可以分批查询、临时表、join 或调整业务模型。

### 9.5 逻辑删除和唯一索引冲突

例如用户名唯一：

```text
username unique
```

用户删除后再创建同名用户可能冲突。可以设计联合唯一索引：

```text
username + del_flag
```

但如果 `del_flag` 只有 0/1，也可能无法支持多次删除同名数据。需要结合业务设计删除时间、删除版本或物理归档策略。

---

## 十、面试高频回答模板

### 10.1 MyBatis-Plus 和 MyBatis 的关系

> MyBatis-Plus 是 MyBatis 的增强，不改变 MyBatis 的核心执行链。它主要提供通用 CRUD、Wrapper、分页、逻辑删除、乐观锁、自动填充、多租户等能力。它能减少重复代码，但复杂 SQL、索引优化和事务设计仍然要开发者自己把控。

### 10.2 Wrapper 有什么优缺点

> Wrapper 的优点是能用链式 API 构造动态条件，LambdaWrapper 还能避免字段名硬编码，适合普通列表查询和条件查询。缺点是复杂 SQL 可读性会下降，不适合多表复杂 join、复杂聚合和需要精细优化的 SQL。我的习惯是简单动态条件用 Wrapper，复杂查询回到 XML，并配合 Explain 看执行计划。

### 10.3 分页插件原理

> 分页插件本质是 MyBatis 拦截器，会在执行 SQL 前改写 SQL，生成 count 查询和分页查询。它能简化分页代码，但复杂 SQL 的 count 可能很慢或不准确，所以大表分页要有索引，深分页要避免直接 offset，复杂场景可以自定义 count 或改成游标分页。

### 10.4 逻辑删除有什么坑

> 逻辑删除是把 delete 转成 update 删除标识，查询时自动过滤未删除数据。它的坑主要是手写 SQL 容易漏删除条件，唯一索引要考虑已删除数据，数据长期不物理删除会膨胀，导出和统计也要明确是否包含删除数据。所以逻辑删除通常还要配归档清理策略。

### 10.5 多租户怎么保证不串数据

> 多租户通常在 SQL 层自动追加 tenant_id 条件，但只靠插件还不够。要确保手写 SQL、缓存 key、文件路径、消息主题、搜索索引、异步任务上下文都带租户维度。超级管理员绕过、公共表忽略租户、定时任务租户来源也要有明确规则，否则很容易出现串租户数据。

