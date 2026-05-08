# SSM 高频面试速记版

> 这份文档适合面试前 20-40 分钟快速过一遍。它不是完整知识展开，而是帮你把 SSM 的高频题压缩成“能快速回忆、能直接开口”的版本。建议配合 [SSM面试实用学习文档.md](/E:/workSpace/idea/JeecgBoot/docs/SSM面试实用学习文档.md) 一起使用。

## 目录

- [一、Spring 容器速记](#一spring-容器速记)
- [二、Bean 生命周期速记](#二bean-生命周期速记)
- [三、IoC 与依赖注入速记](#三ioc-与依赖注入速记)
- [四、循环依赖速记](#四循环依赖速记)
- [五、AOP 速记](#五aop-速记)
- [六、事务速记](#六事务速记)
- [七、Spring MVC 速记](#七spring-mvc-速记)
- [八、MyBatis 速记](#八mybatis-速记)
- [九、常见坑点速记](#九常见坑点速记)
- [十、10 个高频题一页带走](#十10-个高频题一页带走)

---

## 一、Spring 容器速记

### 1.1 Spring 容器启动主线

```text
refresh()
  -> 加载 BeanDefinition
  -> 执行 BeanFactoryPostProcessor
  -> 注册 BeanPostProcessor
  -> 实例化非懒加载单例 Bean
  -> 发布容器刷新完成事件
```

### 1.2 一句话版

> Spring 先准备 Bean 元数据和扩展点，再批量创建单例 Bean，不是上来就直接 new 对象。

### 1.3 两个关键扩展点

| 扩展点 | 改什么 |
| --- | --- |
| `BeanFactoryPostProcessor` | 改 BeanDefinition |
| `BeanPostProcessor` | 改 Bean 实例 |

记忆口诀：

- `BFPP` 改图纸
- `BPP` 改成品

---

## 二、Bean 生命周期速记

### 2.1 标准回答顺序

```text
实例化
  -> 属性填充
  -> Aware 回调
  -> 初始化前置处理
  -> 初始化方法
  -> 初始化后置处理
  -> 使用
  -> 销毁
```

### 2.2 两个最重要的点

1. 依赖注入发生在属性填充阶段
2. AOP 代理通常发生在初始化后的后置处理器阶段

### 2.3 初始化常见入口

- `@PostConstruct`
- `InitializingBean#afterPropertiesSet`
- `init-method`

### 2.4 销毁常见入口

- `@PreDestroy`
- `DisposableBean#destroy`
- `destroy-method`

---

## 三、IoC 与依赖注入速记

### 3.1 IoC 是什么

> 不只是把对象创建交给容器，而是把对象依赖关系、生命周期和扩展织入能力统一交给容器管理。

### 3.2 BeanDefinition 是什么

> Bean 的元数据描述，包含类、作用域、依赖、初始化方法、销毁方法等。Spring 很多能力都先作用在 BeanDefinition 阶段。

### 3.3 `@Autowired` 的底层关键字

```text
AutowiredAnnotationBeanPostProcessor
  -> resolveDependency()
```

### 3.4 多实现类注入如何决策

```text
按类型找
  -> 看是否唯一
  -> 不唯一看 @Primary
  -> 再不唯一看 @Qualifier
  -> 还不行就报错
```

---

## 四、循环依赖速记

### 4.1 哪些能解决

- 单例 Bean
- setter/字段注入

### 4.2 哪些不能解决

- 构造器循环依赖
- 原型 Bean 循环依赖

### 4.3 三级缓存

| 缓存 | 存什么 |
| --- | --- |
| 一级 `singletonObjects` | 完整单例 Bean |
| 二级 `earlySingletonObjects` | 早期 Bean 引用 |
| 三级 `singletonFactories` | 早期引用工厂 |

### 4.4 为什么要三级缓存

> 不是为了多存一层，而是为了配合 AOP，在提前曝光时延迟决定给出去的是原始对象还是代理对象。

### 4.5 高频一句话

> Spring 解决的是单例 setter 循环依赖，核心是实例化后、初始化前的早期曝光机制。

---

## 五、AOP 速记

### 5.1 AOP 是什么

> 在不改业务代码的前提下，把日志、事务、权限、审计等横切逻辑织入方法调用过程。

### 5.2 核心概念

| 概念 | 含义 |
| --- | --- |
| Aspect | 切面 |
| JoinPoint | 连接点 |
| Pointcut | 切点 |
| Advice | 增强 |
| Advisor | Pointcut + Advice |
| Proxy | 代理对象 |

### 5.3 代理方式

| 方式 | 特点 |
| --- | --- |
| JDK 动态代理 | 基于接口 |
| CGLIB | 基于子类 |

### 5.4 AOP 主链

```text
外部调用代理对象
  -> 匹配增强器
  -> 形成拦截器链
  -> 调目标方法
```

### 5.5 为什么 Spring AOP 只支持方法级别

> 因为它基于代理实现，天然围绕方法调用做增强。

### 5.6 最常见失效场景

1. 自调用
2. 目标对象不是 Spring Bean
3. 切点没匹配上
4. `final` 类或方法带来代理限制

---

## 六、事务速记

### 6.1 一句话定义

> Spring 声明式事务本质是 AOP，核心是代理拦截 + 连接绑定 + 提交回滚。

### 6.2 执行主线

```text
进入事务代理
  -> TransactionInterceptor
  -> 开启或加入事务
  -> 获取连接并绑定当前线程
  -> 执行业务方法
  -> 提交或回滚
```

### 6.3 为什么同一事务里多个 SQL 用的是同一连接

> 因为连接通过 `TransactionSynchronizationManager` 绑定到了当前线程。

### 6.4 高频传播行为

| 传播行为 | 含义 |
| --- | --- |
| `REQUIRED` | 有就加入，没有就新建 |
| `REQUIRES_NEW` | 挂起当前事务，自己新开 |
| `SUPPORTS` | 有就加入，没有就非事务 |
| `NESTED` | 当前事务内建保存点 |

### 6.5 默认回滚规则

> 默认对 `RuntimeException` 和 `Error` 回滚，对受检异常默认不回滚。

### 6.6 事务失效高频原因

1. 自调用
2. 非 `public` 方法
3. 异常被吞
4. 受检异常没配 `rollbackFor`
5. 异步线程

---

## 七、Spring MVC 速记

### 7.1 MVC 主入口

```java
DispatcherServlet#doDispatch
```

### 7.2 请求主链

```text
请求进来
  -> HandlerMapping 找处理器
  -> HandlerAdapter 调方法
  -> 参数解析
  -> 执行 Controller
  -> 返回值处理
  -> 视图渲染或 JSON 输出
```

### 7.3 参数为什么能自动绑定

> 因为 Spring MVC 在调用 Controller 前，会通过 `HandlerMethodArgumentResolver` 解析每个方法参数。

### 7.4 `@RequestBody` 为什么能转对象

> 因为有 `HttpMessageConverter` 把请求体字节流转换成 Java 对象。

### 7.5 全局异常处理为什么推荐 `@ControllerAdvice`

> 因为它能统一收口异常边界、日志策略和返回格式。

### 7.6 Filter 和 Interceptor

| 对比项 | Filter | Interceptor |
| --- | --- | --- |
| 层级 | Servlet | Spring MVC |
| 生效位置 | 更前 | 更靠近 Controller |
| 常见用途 | 编码、跨域、包装请求 | 登录、权限、埋点 |

---

## 八、MyBatis 速记

### 8.1 Mapper 为什么能直接调用

> 因为 Mapper 最终是动态代理对象，不是接口本体。

### 8.2 执行主链

```text
MapperProxy
  -> SqlSession
  -> Executor
  -> StatementHandler
  -> JDBC
  -> Database
```

### 8.3 核心对象分工

| 对象 | 职责 |
| --- | --- |
| `SqlSession` | 执行入口 |
| `Executor` | 执行调度、缓存、事务协同 |
| `StatementHandler` | JDBC Statement 执行 |
| `ParameterHandler` | 参数绑定 |
| `ResultSetHandler` | 结果映射 |
| `TypeHandler` | 类型转换 |

### 8.4 `#{} 和 ${}` 区别

| 写法 | 本质 |
| --- | --- |
| `#{}` | 预编译占位符，安全 |
| `${}` | 字符串拼接，风险高 |

### 8.5 一级缓存和二级缓存

| 缓存 | 范围 |
| --- | --- |
| 一级缓存 | `SqlSession` 级别 |
| 二级缓存 | namespace 级别 |

工程建议：

- 一级缓存自然用
- 二级缓存谨慎用

### 8.6 Spring 为什么能管住 MyBatis 事务

> 因为 Spring 整合 MyBatis 后，`SqlSession` 和连接获取会接入 Spring 的事务资源管理体系。

---

## 九、常见坑点速记

### 9.1 Spring

1. 字段注入太多，依赖关系不透明
2. 构造器循环依赖
3. 在初始化阶段做过重逻辑

### 9.2 AOP / 事务

1. 自调用失效
2. 异常被吞不回滚
3. 异步线程事务失效
4. `final` 方法不能按预期增强

### 9.3 MVC

1. 参数绑定理解不清
2. 没做全局异常收口
3. 拦截器做重逻辑

### 9.4 MyBatis

1. `${}` 注入风险
2. N+1 查询
3. 批量操作没真正走批量
4. 二级缓存脏数据

---

## 十、10 个高频题一页带走

### 10.1 什么是 IoC

> IoC 是把对象依赖关系、生命周期和扩展织入统一交给容器管理，不只是把对象创建交给 Spring。

### 10.2 Spring Bean 生命周期

> 实例化、属性填充、Aware 回调、初始化前置处理、初始化方法、初始化后置处理、使用、销毁。AOP 常在初始化后阶段织入。

### 10.3 BeanFactoryPostProcessor 和 BeanPostProcessor 区别

> 前者操作 BeanDefinition，后者操作 Bean 实例。

### 10.4 Spring 如何解决循环依赖

> 通过单例 Bean 的三级缓存和早期曝光机制解决 setter/字段注入循环依赖，构造器循环依赖解决不了。

### 10.5 AOP 底层原理

> 基于 JDK 动态代理或 CGLIB 生成代理对象，调用时按拦截器链增强目标方法。

### 10.6 为什么事务本质也是 AOP

> 因为 `@Transactional` 也是方法拦截，代理里开启事务、绑定连接、决定提交回滚。

### 10.7 为什么事务会失效

> 常见是自调用、异常被吞、非 public、受检异常未配置回滚、异步线程切换。

### 10.8 Spring MVC 请求怎么走

> `DispatcherServlet` 统一调度，HandlerMapping 找方法，HandlerAdapter 调方法，参数解析器绑定参数，返回值处理器输出结果。

### 10.9 Mapper 为什么能执行 SQL

> 因为 Mapper 是动态代理对象，调用方法时会转成 `MappedStatement`，交给 `SqlSession` 和 `Executor` 执行。

### 10.10 `#{} 和 ${}` 区别

> `#{}` 走预编译参数绑定，`${}` 直接字符串拼接，安全性差。

---

## 最后怎么用这份速记版

建议你这样用：

1. 面试前先看这份，强行把主线拉起来。
2. 对答不顺的题，回到 [SSM面试实用学习文档.md](/E:/workSpace/idea/JeecgBoot/docs/SSM面试实用学习文档.md) 重新展开。
3. 对想讲得更底层的题，再看 [SSM源码深挖版.md](/E:/workSpace/idea/JeecgBoot/docs/SSM源码深挖版.md)。

这样三份文档就形成了：

```text
速记版：临门一脚
  -> 实用版：系统复习
  -> 源码版：深挖原理
```
