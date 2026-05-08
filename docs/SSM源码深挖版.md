# SSM 源码深挖版

> 这份文档不是替代 [SSM面试实用学习文档.md](/E:/workSpace/idea/JeecgBoot/docs/SSM面试实用学习文档.md)，而是继续往下挖：把核心类、关键调用链、断点位置、阅读顺序和常见源码题串起来。适合已经会用 SSM，准备把“会说”升级成“看过、调过、能解释”的工程师。

![SSM 深度复习知识地图](images/ssm-01-knowledge-map.svg)

## 目录

- [一、怎么读 SSM 源码最有效](#一怎么读-ssm-源码最有效)
- [二、Spring 容器源码主线](#二spring-容器源码主线)
- [三、Bean 创建源码主线](#三bean-创建源码主线)
- [四、依赖注入与依赖查找源码主线](#四依赖注入与依赖查找源码主线)
- [五、循环依赖与三级缓存源码主线](#五循环依赖与三级缓存源码主线)
- [六、AOP 源码主线](#六aop-源码主线)
- [七、事务源码主线](#七事务源码主线)
- [八、Spring MVC 源码主线](#八spring-mvc-源码主线)
- [九、MyBatis 源码主线](#九mybatis-源码主线)
- [十、Spring 整合 MyBatis 的源码主线](#十spring-整合-mybatis-的源码主线)
- [十一、建议断点位与调试方法](#十一建议断点位与调试方法)
- [十二、源码题常见回答模板](#十二源码题常见回答模板)
- [十三、推荐阅读顺序](#十三推荐阅读顺序)

---

## 一、怎么读 SSM 源码最有效

很多人读源码读着读着就迷路，原因通常有两个：

1. 一上来就铺开读，类太多。
2. 只看类，不抓主调用链。

对 SSM 这类框架，最有效的方法是：

### 1.1 只抓主链，不追所有细节

比如 Spring 容器，不要一开始就想把所有 BeanFactory、PostProcessor、Aware、Scope 都看全。  
先抓住：

```text
refresh()
  -> finishBeanFactoryInitialization()
  -> preInstantiateSingletons()
  -> getBean()
  -> doGetBean()
  -> createBean()
  -> doCreateBean()
```

只要这条主线通了，再往分支补细节，阅读效率会高很多。

### 1.2 每块只盯一个入口类

建议你按这个方式抓入口：

| 模块 | 首选入口类 |
| --- | --- |
| Spring 容器 | `AbstractApplicationContext` |
| Bean 创建 | `AbstractAutowireCapableBeanFactory` |
| 单例注册 | `DefaultSingletonBeanRegistry` |
| AOP | `AbstractAutoProxyCreator` |
| 事务 | `TransactionInterceptor` |
| MVC | `DispatcherServlet` |
| MyBatis | `MapperProxy`、`DefaultSqlSession` |

### 1.3 用“先调试、后精读”的方式

纯看代码很容易抽象化。你最好自己准备一个极简 demo：

- 一个 `@Component`
- 一个 `@Autowired`
- 一个循环依赖 Bean
- 一个 `@Aspect`
- 一个 `@Transactional`
- 一个 MVC Controller
- 一个简单 Mapper

然后边跑边打断点。

---

## 二、Spring 容器源码主线

![Spring 容器启动流程](images/ssm-02-spring-refresh.svg)

Spring 容器启动最重要的入口是：

```java
AbstractApplicationContext#refresh()
```

### 2.1 `refresh()` 这条链到底在做什么

核心可以压缩成六步：

1. 准备上下文环境
2. 创建或刷新 `BeanFactory`
3. 执行 `BeanFactoryPostProcessor`
4. 注册 `BeanPostProcessor`
5. 实例化非懒加载单例 Bean
6. 完成刷新并发布事件

你可以把它理解成：

```text
容器框架先搭起来
  -> Bean 图纸准备好
  -> 图纸允许被修改
  -> Bean 创建增强器注册好
  -> 再真正批量创建 Bean
```

### 2.2 建议先看这几个方法

```java
AbstractApplicationContext#refresh
AbstractApplicationContext#invokeBeanFactoryPostProcessors
AbstractApplicationContext#registerBeanPostProcessors
AbstractApplicationContext#finishBeanFactoryInitialization
DefaultListableBeanFactory#preInstantiateSingletons
```

### 2.3 `DefaultListableBeanFactory` 为什么重要

因为它既是 BeanDefinition 注册中心，也是 Bean 查找和创建调度的重要实现类。

它身上你至少要有三个印象：

1. 管 BeanDefinition
2. 管依赖解析
3. 管单例预实例化

### 2.4 `preInstantiateSingletons()` 做了什么

这个方法是 Spring 大批量创建非懒加载单例 Bean 的关键入口。

它会遍历 BeanDefinition，针对：

- 单例
- 非抽象
- 非懒加载

的 Bean 触发 `getBean()`。

这也意味着：  
**Spring 容器不是启动时一次性把所有对象“new 出来”那么简单，而是有选择地对满足条件的 Bean 做预实例化。**

---

## 三、Bean 创建源码主线

![Spring Bean 生命周期](images/ssm-03-bean-lifecycle.svg)

真正创建 Bean 的主链大体如下：

```text
getBean()
  -> doGetBean()
  -> getSingleton()
  -> createBean()
  -> doCreateBean()
  -> createBeanInstance()
  -> populateBean()
  -> initializeBean()
```

### 3.1 `doGetBean()` 是 Bean 获取总入口

这个方法要解决几件事：

1. 先看单例池有没有
2. 没有的话要不要创建
3. 是单例、原型还是其他 scope
4. 如果是依赖 Bean，还要先确保依赖就绪

面试里如果你能说出：  
**Spring 获取 Bean 的总入口是 `doGetBean()`，创建 Bean 的总入口是 `doCreateBean()`**，就已经比很多人扎实了。

### 3.2 `createBean()` 不是直接 new

`createBean()` 内部会先尝试：

- 是否需要前置代理
- 是否有自定义目标类型解析
- 是否存在代理短路

如果这些前置逻辑没有截断，才会进入 `doCreateBean()`。

### 3.3 `doCreateBean()` 是最值得盯的方法

建议你把这个方法至少看三遍。

它里面基本串起了：

- 实例化
- 提前曝光
- 依赖注入
- 初始化
- 后置处理器
- 代理包装

### 3.4 `createBeanInstance()` 关心的是“怎么造对象”

这里会涉及：

- 构造器推断
- 工厂方法
- 自动装配构造器
- `Supplier`

如果你被问到“Spring 如何选择构造器”，就要往这里靠。

### 3.5 `populateBean()` 关心的是“怎么注入依赖”

这个方法是属性填充的核心入口。

里面会串到：

- `InstantiationAwareBeanPostProcessor`
- 自动装配属性解析
- `@Autowired` 之类注解驱动注入

### 3.6 `initializeBean()` 关心的是“怎么完成初始化”

它会依次处理：

1. `Aware` 回调
2. 初始化前 `BeanPostProcessor`
3. 初始化方法
4. 初始化后 `BeanPostProcessor`

而 AOP 代理很多时候就是在初始化后阶段织入的。

---

## 四、依赖注入与依赖查找源码主线

### 4.1 `@Autowired` 最终会走到哪里

如果你从注解往下跟，常见会遇到：

```text
AutowiredAnnotationBeanPostProcessor
  -> postProcessProperties()
  -> InjectionMetadata.inject()
  -> DefaultListableBeanFactory.resolveDependency()
```

真正关键的是：

```java
DefaultListableBeanFactory#resolveDependency
```

### 4.2 `resolveDependency()` 在解决什么问题

它不是简单地“按名字取 Bean”，而是在做：

1. 识别依赖描述
2. 按类型找候选 Bean
3. 过滤不可注入候选
4. 结合 `@Primary`、`@Qualifier`、Bean 名称做决策
5. 处理集合、数组、Map、`ObjectProvider` 等特殊注入

### 4.3 依赖描述对象值得记一下

`DependencyDescriptor`

它可以理解成：

- 注入点长什么样
- 类型是什么
- 是否必须
- 是否支持延迟解析

这个类在源码题里很加分，因为很多人只知道 `@Autowired`，不知道 Spring 内部其实先把注入点描述成一个对象再去解析。

### 4.4 多实现类为什么会冲突

因为 Spring 的默认策略是：

1. 先按类型找候选
2. 候选唯一就注入
3. 不唯一再看 `@Primary`
4. 再不唯一看 `@Qualifier`
5. 还不唯一就抛异常

所以“找不到”和“找太多了”是两种完全不同的问题。

---

## 五、循环依赖与三级缓存源码主线

![Spring 三级缓存与循环依赖](images/ssm-04-circular-dependency.svg)

循环依赖源码里，最值得盯的是两个类：

- `AbstractAutowireCapableBeanFactory`
- `DefaultSingletonBeanRegistry`

### 5.1 三个核心缓存在哪

通常你会看到这些结构：

```java
singletonObjects
earlySingletonObjects
singletonFactories
```

对应：

- 一级缓存：完整 Bean
- 二级缓存：早期 Bean 引用
- 三级缓存：早期引用工厂

### 5.2 真正关键的方法

建议重点看：

```java
DefaultSingletonBeanRegistry#getSingleton
DefaultSingletonBeanRegistry#addSingletonFactory
DefaultSingletonBeanRegistry#addSingleton
AbstractAutowireCapableBeanFactory#doCreateBean
```

### 5.3 源码层面的关键时机

Spring 解决循环依赖，关键不是“有缓存”，而是这个时机：

```text
实例化完成
  -> 还没属性填充和初始化
  -> 先暴露 ObjectFactory
```

为什么要在这里暴露？

因为：

- 此时对象已经有了
- 但依赖还没填完
- 刚好可以给其他 Bean 一个“先用引用”的机会

### 5.4 为什么是 `ObjectFactory`

因为 Spring 不想太早决定暴露什么。

有些 Bean 后面可能会被 AOP 代理，那提前暴露时：

- 暴露原始对象？
- 暴露代理对象？

这件事要延迟决策。

所以三级缓存的本质不是“多存一层”，而是 **延迟决定早期引用的具体形态**。

### 5.5 一个很值得记住的源码结论

> 三级缓存服务的不是循环依赖本身，而是“循环依赖 + AOP 代理早期曝光”这个组合场景。

这句话在面试里非常好用。

---

## 六、AOP 源码主线

![Spring AOP 代理调用链](images/ssm-05-aop-proxy.svg)

Spring AOP 的源码阅读，建议抓两条线：

1. 代理对象什么时候创建
2. 方法调用时拦截器链怎么执行

### 6.1 代理创建入口

建议重点跟：

```java
AbstractAutoProxyCreator#postProcessAfterInitialization
AbstractAutoProxyCreator#wrapIfNecessary
```

也就是说，AOP 通常是通过 `BeanPostProcessor` 接进来的。

### 6.2 为什么 `AbstractAutoProxyCreator` 重要

因为它承担了这些事：

- 判断当前 Bean 要不要代理
- 找符合条件的增强器
- 创建代理对象

### 6.3 创建代理时会做什么判断

大体会判断：

- 是否是基础设施类
- 是否匹配切点
- 是否已有缓存结果
- 该走 JDK 代理还是 CGLIB

### 6.4 代理工厂相关类建议眼熟

```java
ProxyFactory
AdvisedSupport
JdkDynamicAopProxy
CglibAopProxy
```

你不用一开始就全啃完，但要知道职责：

- `ProxyFactory`：组装代理
- `AdvisedSupport`：保存增强配置
- `JdkDynamicAopProxy`：JDK 代理调用处理
- `CglibAopProxy`：CGLIB 代理生成

### 6.5 方法调用时怎么走拦截器链

JDK 代理时常见入口：

```java
JdkDynamicAopProxy#invoke
```

继续往下会遇到：

```java
ReflectiveMethodInvocation#proceed
```

这就是拦截器链的灵魂方法。

你可以把它想成：

```text
当前拦截器执行
  -> 调用 proceed()
  -> 下一个拦截器执行
  -> 最终目标方法执行
```

这也是为什么 Spring AOP 很适合讲责任链模式。

### 6.6 `Advisor`、`Advice`、`MethodInterceptor` 的关系

源码层面上可以这么理解：

- `Advice` 是增强动作抽象
- `Pointcut` 决定匹配范围
- `Advisor` 把两者组合起来
- 最终很多增强会适配成 `MethodInterceptor`

这体现了 Spring 很典型的设计风格：  
**先抽象概念，再适配成统一调用模型。**

---

## 七、事务源码主线

![Spring 声明式事务流程](images/ssm-06-transaction-flow.svg)

事务阅读源码时，强烈建议不要从注解本身开始，而是从拦截器开始。

### 7.1 最核心入口

```java
TransactionInterceptor#invoke
TransactionAspectSupport#invokeWithinTransaction
```

这条链基本能串起声明式事务的主干。

### 7.2 `invokeWithinTransaction()` 在做什么

它核心做四件事：

1. 获取事务属性
2. 选择事务管理器
3. 开启/加入事务
4. 调用目标方法后提交或回滚

### 7.3 事务状态在哪里保存

事务运行时很多状态会绑定到当前线程。

重点类：

```java
TransactionSynchronizationManager
```

你至少要知道它管这些东西：

- 连接资源
- 当前事务名
- 是否只读
- 同步回调集合
- 事务激活状态

### 7.4 `DataSourceTransactionManager` 为什么值得看

如果你现在主要做单库事务，它是最值得看的事务管理器实现。

重点方法建议看：

```java
doBegin()
doCommit()
doRollback()
doCleanupAfterCompletion()
```

看完你会发现事务没那么玄学，本质就是：

- 拿连接
- 关自动提交
- 执行业务
- 提交或回滚
- 解绑资源

### 7.5 自调用事务失效，源码上为什么成立

原因不在事务，而在代理。

当外部进来时：

- 先走代理
- 再走 `TransactionInterceptor`

但如果类内部 `this.xxx()` 调自己：

- 直接打到目标对象
- 没经过代理
- 当然也就不会进事务拦截器

这就是“源码级解释”，不是口诀级解释。

---

## 八、Spring MVC 源码主线

![Spring MVC 请求处理链](images/ssm-07-mvc-request.svg)

MVC 的源码主入口非常明确：

```java
DispatcherServlet#doDispatch
```

### 8.1 `doDispatch()` 主链

可以先压缩成：

```text
查 Handler
  -> 找适配器
  -> 执行拦截器 preHandle
  -> 调 Handler
  -> 处理返回值
  -> 执行拦截器 afterCompletion
```

### 8.2 为什么先看 `DispatcherServlet`

因为它是 MVC 的调度中心。  
Controller、参数绑定、异常处理、返回值处理，全都被它串起来。

### 8.3 Handler 是怎么找到的

建议看：

```java
DispatcherServlet#getHandler
AbstractHandlerMethodMapping#getHandlerInternal
```

如果是注解驱动 Controller，最终通常会落到 `HandlerMethod`。

### 8.4 方法为什么能被正确调用

重点类：

```java
RequestMappingHandlerAdapter
InvocableHandlerMethod
ServletInvocableHandlerMethod
```

这里面串起了：

- 参数解析器
- 数据绑定
- 方法反射调用
- 返回值处理器

### 8.5 参数解析器建议重点看什么

```java
HandlerMethodArgumentResolver
HandlerMethodArgumentResolverComposite
```

你会更容易理解：

- `@RequestParam`
- `@PathVariable`
- `@RequestBody`

为什么能工作。

### 8.6 返回值处理器建议重点看什么

```java
HandlerMethodReturnValueHandler
RequestResponseBodyMethodProcessor
```

其中 `RequestResponseBodyMethodProcessor` 很关键，因为它和消息转换器一起处理 `@ResponseBody` / `@RestController` 场景。

### 8.7 异常处理主线

可以关注：

```java
HandlerExceptionResolver
ExceptionHandlerExceptionResolver
```

这样你就能从源码层面理解：

- `@ExceptionHandler`
- `@ControllerAdvice`

到底怎么接管异常的。

---

## 九、MyBatis 源码主线

![MyBatis SQL 执行链路](images/ssm-10-mybatis-execution.svg)

MyBatis 推荐抓两条线：

1. Mapper 方法为什么能执行
2. SQL 真正怎么跑到数据库

### 9.1 Mapper 动态代理入口

重点类：

```java
MapperProxy
MapperMethod
MapperProxyFactory
```

建议你先从：

```java
MapperProxy#invoke
```

开始看。

### 9.2 `MapperMethod` 负责什么

它不是执行 SQL，而是把 Mapper 接口方法抽象成“可执行命令”，包括：

- SQL 类型
- 返回值特征
- 参数映射方式

### 9.3 `SqlSession` 是真正对外会话入口

重点类：

```java
DefaultSqlSession
```

重点方法：

```java
selectOne
selectList
insert
update
delete
```

### 9.4 `Executor` 是执行调度核心

建议先看接口，再看实现：

```java
Executor
BaseExecutor
SimpleExecutor
ReuseExecutor
BatchExecutor
CachingExecutor
```

职责大概是：

- 执行 SQL
- 维护一级缓存
- 配合事务
- 包装缓存逻辑

### 9.5 `StatementHandler`、`ParameterHandler`、`ResultSetHandler`

这三个是 MyBatis 很值得掌握的“执行分工”。

| 组件 | 干什么 |
| --- | --- |
| `StatementHandler` | 创建和执行 JDBC Statement |
| `ParameterHandler` | 给占位符设值 |
| `ResultSetHandler` | 结果集映射为对象 |

如果面试官问你“为什么 MyBatis 易扩展”，你就可以往这里靠，因为这几个点都可以被插件拦截。

### 9.6 `MappedStatement` 为什么是核心元数据

Spring 有 `BeanDefinition`，MyBatis 有 `MappedStatement`。

它描述了：

- SQL 来源
- 参数映射
- 结果映射
- 缓存配置
- 语句类型

所以 MyBatis 也是典型的：

**先元数据建模，再运行时执行。**

---

## 十、Spring 整合 MyBatis 的源码主线

![Spring 整合 MyBatis](images/ssm-13-spring-mybatis.svg)

这块是实际项目里很常用，但很多人源码上没真正理解的一块。

### 10.1 Mapper 是怎么注册成 Spring Bean 的

重点看这些类：

```java
MapperScannerConfigurer
ClassPathMapperScanner
MapperFactoryBean
SqlSessionFactoryBean
SqlSessionTemplate
```

### 10.2 关键理解一：Mapper 接口不是直接实例化

Mapper 是接口，不能直接 new。  
所以 Spring 整合 MyBatis 时，会为它注册 `MapperFactoryBean`，最终由它生成代理对象。

### 10.3 关键理解二：`SqlSessionTemplate` 很重要

它的价值是把 MyBatis 的 `SqlSession` 使用方式托管给 Spring。

你可以把它理解成：

- 帮你拿 `SqlSession`
- 帮你接入 Spring 事务
- 帮你处理会话生命周期

这就是为什么在 Spring 环境里不推荐你自己手撸原始 `SqlSession`。

### 10.4 为什么 Spring 事务能管住 MyBatis

本质上是因为：

- MyBatis 最终取连接时
- 会通过 Spring 管理的资源体系
- 拿到当前线程绑定的连接

所以两个世界不是“各管各的”，而是整合后变成了一条资源链。

---

## 十一、建议断点位与调试方法

这部分很实用。你如果真准备深挖，建议按主题打一轮断点。

### 11.1 Spring 容器

```java
AbstractApplicationContext#refresh
DefaultListableBeanFactory#preInstantiateSingletons
AbstractBeanFactory#doGetBean
AbstractAutowireCapableBeanFactory#createBean
AbstractAutowireCapableBeanFactory#doCreateBean
AbstractAutowireCapableBeanFactory#populateBean
AbstractAutowireCapableBeanFactory#initializeBean
```

### 11.2 循环依赖

```java
DefaultSingletonBeanRegistry#getSingleton
DefaultSingletonBeanRegistry#addSingletonFactory
AbstractAutowireCapableBeanFactory#doCreateBean
```

### 11.3 AOP

```java
AbstractAutoProxyCreator#postProcessAfterInitialization
AbstractAutoProxyCreator#wrapIfNecessary
JdkDynamicAopProxy#invoke
ReflectiveMethodInvocation#proceed
```

### 11.4 事务

```java
TransactionInterceptor#invoke
TransactionAspectSupport#invokeWithinTransaction
DataSourceTransactionManager#doBegin
DataSourceTransactionManager#doCommit
DataSourceTransactionManager#doRollback
```

### 11.5 MVC

```java
DispatcherServlet#doDispatch
RequestMappingHandlerAdapter#invokeHandlerMethod
InvocableHandlerMethod#invokeForRequest
RequestResponseBodyMethodProcessor#resolveArgument
RequestResponseBodyMethodProcessor#handleReturnValue
```

### 11.6 MyBatis

```java
MapperProxy#invoke
MapperMethod#execute
DefaultSqlSession#selectList
CachingExecutor#query
SimpleExecutor#doQuery
PreparedStatementHandler#query
DefaultResultSetHandler#handleResultSets
```

### 11.7 调试建议

每次只验证一个问题，不要一锅端：

1. 验证 Bean 生命周期
2. 验证循环依赖提前曝光
3. 验证 AOP 代理创建
4. 验证事务连接绑定
5. 验证 MVC 参数绑定
6. 验证 MyBatis 执行链

---

## 十二、源码题常见回答模板

### 12.1 Spring Bean 是怎么创建的

> Spring 获取 Bean 的总入口是 `doGetBean()`，创建 Bean 的核心入口是 `doCreateBean()`。它会先实例化对象，再在必要时提前曝光早期引用，然后完成属性填充、Aware 回调、初始化方法和前后置处理器调用。AOP 代理通常在初始化后阶段通过 BeanPostProcessor 包装进去。

### 12.2 Spring 为什么能解决部分循环依赖

> 因为单例 Bean 在实例化后、初始化前可以先通过三级缓存暴露一个早期引用。其他 Bean 依赖它时，就能先拿到这个早期引用完成依赖注入。三级缓存存在的关键价值是配合 AOP，让 Spring 延迟决定暴露的是原始对象还是代理对象。构造器循环依赖由于对象尚未实例化，所以无法解决。

### 12.3 Spring AOP 代理链是怎么跑的

> Spring 在 Bean 初始化后阶段判断是否需要为当前 Bean 创建代理。外部方法调用先进入代理对象，代理会根据当前方法匹配到一组 Advisor，并适配成拦截器链，最终通过 `ReflectiveMethodInvocation#proceed()` 递归推进，最后落到目标方法执行。

### 12.4 事务底层源码链怎么讲

> 声明式事务核心入口是 `TransactionInterceptor#invoke` 和 `TransactionAspectSupport#invokeWithinTransaction`。它会先解析事务属性，选择事务管理器，再开启或加入事务，并通过 `TransactionSynchronizationManager` 绑定连接到当前线程。目标方法执行结束后，根据结果决定提交还是回滚。

### 12.5 Spring MVC 请求是怎么到 Controller 的

> `DispatcherServlet#doDispatch` 是 MVC 主入口。它先通过 HandlerMapping 找到 Handler，再通过 HandlerAdapter 执行。执行过程中会用参数解析器完成方法入参绑定，用返回值处理器和消息转换器处理输出，用异常解析器统一接管异常。

### 12.6 MyBatis Mapper 为什么能直接调用

> 因为 Mapper 最终是动态代理对象。调用接口方法时会进入 `MapperProxy#invoke`，再由 `MapperMethod` 把当前方法转换成具体 SQL 执行语义，交给 `SqlSession` 和 `Executor` 执行。SQL 最终通过 `StatementHandler`、`ParameterHandler` 和 `ResultSetHandler` 完成 JDBC 调用和结果映射。

---

## 十三、推荐阅读顺序

如果你打算花 1 到 2 周系统深挖，我建议这样排：

### 第 1 阶段：Spring 容器

1. `AbstractApplicationContext#refresh`
2. `DefaultListableBeanFactory#preInstantiateSingletons`
3. `AbstractBeanFactory#doGetBean`
4. `AbstractAutowireCapableBeanFactory#doCreateBean`

目标：把“容器启动”和“Bean 创建”串起来。

### 第 2 阶段：生命周期和循环依赖

1. `populateBean`
2. `initializeBean`
3. `DefaultSingletonBeanRegistry#getSingleton`

目标：把“依赖注入、初始化、三级缓存”讲顺。

### 第 3 阶段：AOP 和事务

1. `AbstractAutoProxyCreator`
2. `JdkDynamicAopProxy`
3. `ReflectiveMethodInvocation`
4. `TransactionInterceptor`

目标：把“代理创建、拦截器链、事务增强”串起来。

### 第 4 阶段：MVC

1. `DispatcherServlet#doDispatch`
2. `RequestMappingHandlerAdapter`
3. 参数解析器 / 返回值处理器

目标：把“请求是怎么调到 Controller 的”讲顺。

### 第 5 阶段：MyBatis

1. `MapperProxy`
2. `DefaultSqlSession`
3. `Executor`
4. `StatementHandler`

目标：把“接口调用如何变成 SQL 执行”讲顺。

---

## 最后建议

源码阅读最怕贪多。你现在这个阶段，不需要把 SSM 所有类都背下来，真正值钱的是：

- 说得出主链
- 找得到入口类
- 知道断点该下哪
- 能解释一个机制为什么这样设计

如果你把这份文档和前一份主文档配合着过一遍，SSM 这一块已经能从“会用”往“理解框架运行本质”走得很扎实了。
