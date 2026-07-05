# SSM 从源码到面试总稿

> 这是一份把原来的 `SSM源码深挖版`、`SSM面试实用学习文档`、`SSM高频面试速记版` 合在一起的总稿。  
> 如果你时间不多，建议只看这一份，按“先主线、后细节、再速记”的顺序读。

![SSM 深度复习知识地图](images/ssm-01-knowledge-map.svg)

## 目录

- [一、怎么读这份总稿](#一怎么读这份总稿)
- [二、先认识源码里的几个关键类](#二先认识源码里的几个关键类)
- [三、Spring 容器启动主线](#三spring-容器启动主线)
- [四、Bean 创建与生命周期](#四bean-创建与生命周期)
- [五、IoC、依赖注入与 BeanDefinition](#五ioc依赖注入与-beandefinition)
- [六、循环依赖与三级缓存](#六循环依赖与三级缓存)
- [七、AOP 与声明式事务](#七aop-与声明式事务)
- [八、Spring MVC 请求处理链](#八spring-mvc-请求处理链)
- [九、MyBatis 核心执行链](#九mybatis-核心执行链)
- [十、Spring 与 MyBatis 怎么整合](#十spring-与-mybatis-怎么整合)
- [十一、面试高频题速答](#十一面试高频题速答)
- [十二、推荐复习路线和断点位](#十二推荐复习路线和断点位)

---

## 一、怎么读这份总稿

如果你只剩很少时间，按这个顺序看：

1. 先看二、三、四，搞懂 Spring 容器和 Bean 是怎么来的。
2. 再看六、七，搞懂循环依赖、AOP 和事务。
3. 再看八、九、十，搞懂 MVC 和 MyBatis。
4. 最后看十一，背高频回答。

这份总稿的目标不是把所有源码都啃完，而是让你能回答这几个核心问题：

- `BeanDefinition` 是什么。
- `BeanFactoryPostProcessor` 和 `BeanPostProcessor` 有什么区别。
- Spring 为什么能创建 Bean、注入依赖、解决部分循环依赖。
- `@Transactional` 为什么本质上是 AOP。
- 一个请求为什么能从 `DispatcherServlet` 走到 Controller。
- Mapper 为什么一个接口就能直接执行 SQL。

---

## 二、先认识源码里的几个关键类

下面这几个类，是你看 Spring 源码时最该先认识的。

| 类 | 你可以先这样理解 | 你要记住的点 |
| --- | --- | --- |
| `BeanDefinition` | Bean 的“图纸” | 描述这个 Bean 是什么、怎么创建、什么时候初始化 |
| `BeanFactory` | Bean 的工厂接口 | 负责拿 Bean，不负责调度整套容器生命周期 |
| `ApplicationContext` | 更完整的容器 | 在 `BeanFactory` 基础上加了事件、国际化、资源加载等能力 |
| `DefaultListableBeanFactory` | Spring 容器核心实现 | 既能注册 `BeanDefinition`，又能查找、创建 Bean |
| `AbstractApplicationContext` | `refresh()` 的总调度器 | 容器启动主线大多从这里看 |
| `AbstractAutowireCapableBeanFactory` | 真正创建 Bean 的关键类 | 实例化、属性填充、初始化基本都在这条线上 |
| `BeanFactoryPostProcessor` | 改“图纸”的扩展点 | 在 Bean 实例化前修改 `BeanDefinition` |
| `BeanPostProcessor` | 改“成品”的扩展点 | 在 Bean 创建前后增强 Bean 实例 |
| `InstantiationAwareBeanPostProcessor` | 更早介入的后置处理器 | 能插到实例化前、属性填充前 |
| `SmartInstantiationAwareBeanPostProcessor` | 更聪明的后置处理器 | 循环依赖早期暴露、AOP 代理经常会碰到它 |
| `FactoryBean` | 生产别的对象的 Bean | `getBean("x")` 拿到的是产品，`&x` 才能拿到工厂本身 |
| `SingletonBeanRegistry` | 单例注册中心 | 三层缓存的基础就在这条线上 |

### 2.1 `BeanDefinition` 到底是什么

它不是 Bean 本身，而是 Bean 的元数据描述。常见信息包括：

- `beanClassName`
- 作用域 `scope`
- 是否懒加载 `lazyInit`
- 初始化方法 `initMethodName`
- 销毁方法 `destroyMethodName`
- 构造器参数、属性值、依赖关系

你可以把它理解成“建筑图纸”，Spring 先拿图纸，再决定怎么盖房子。

### 2.2 `BeanFactoryPostProcessor` 和 `BeanPostProcessor` 的区别

最短记法：

- `BeanFactoryPostProcessor` 改图纸，也就是 `BeanDefinition`。
- `BeanPostProcessor` 改成品，也就是实际 Bean 实例。

```java
@Component
public class DemoBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinition bd = beanFactory.getBeanDefinition("userService");
        bd.setLazyInit(true);
    }
}
```

```java
@Component
public class DemoBeanPostProcessor implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof UserService) {
            return bean; // 这里可以做代理、增强、包装
        }
        return bean;
    }
}
```

### 2.3 `FactoryBean` 容易混淆的地方

`FactoryBean` 本身也是一个 Bean，但它返回的不是它自己，而是它生产出来的对象。  
MyBatis 和 Spring 整合里很常见的 `SqlSessionFactoryBean` 就是这个思路。

如果要拿工厂本身，用 `&beanName`；如果直接 `getBean("beanName")`，拿到的是它生产出来的对象。

---

## 三、Spring 容器启动主线

![Spring 容器启动流程](images/ssm-02-spring-refresh.svg)

Spring 容器启动最核心的入口是：

```text
AbstractApplicationContext#refresh()
```

很多人一听 Spring 启动流程，就会马上想到“扫描 Bean、创建 Bean、注入依赖”。这没有错，但顺序还不够准确。

更准确地说，Spring 启动不是一上来就创建对象，而是先准备容器环境，再收集和加工 `BeanDefinition`，然后注册各种后置处理器，最后才批量创建非懒加载的单例 Bean。

可以先把 `refresh()` 理解成一句话：

> 准备环境和容器，加载 Bean 定义，处理 Bean 定义，注册后置处理器，实例化单例 Bean，发布容器启动完成事件。

完整一点的主线如下：

```text
prepareRefresh()
  -> obtainFreshBeanFactory()
  -> prepareBeanFactory()
  -> postProcessBeanFactory()
  -> invokeBeanFactoryPostProcessors()
  -> registerBeanPostProcessors()
  -> initMessageSource()
  -> initApplicationEventMulticaster()
  -> onRefresh()
  -> registerListeners()
  -> finishBeanFactoryInitialization()
  -> finishRefresh()
```

### 3.1 先分清 `BeanFactory` 和 `ApplicationContext`

看启动流程之前，先把两个概念分开。

`BeanFactory` 是底层 Bean 容器，核心能力是：

- 保存 `BeanDefinition`
- 根据名称或类型查找 Bean
- 创建 Bean
- 做依赖注入
- 管理单例缓存

`ApplicationContext` 是更完整的应用上下文，它在 `BeanFactory` 基础上增加了：

- 国际化 `MessageSource`
- 事件发布和监听
- 资源加载
- 环境变量 `Environment`
- 生命周期管理
- Web 容器相关能力

所以你可以这样记：

> 真正创建 Bean 的核心在 `BeanFactory`，调度整套启动流程的是 `ApplicationContext`。

面试里如果问 Spring 容器启动入口，一般答：

```text
AbstractApplicationContext#refresh()
```

如果继续追问 Bean 到底在哪里创建，一般要答到：

```text
DefaultListableBeanFactory#preInstantiateSingletons()
AbstractBeanFactory#doGetBean()
AbstractAutowireCapableBeanFactory#doCreateBean()
```

### 3.2 `prepareRefresh()`：准备启动环境

这一步还没有创建业务 Bean，主要是做容器启动前的状态准备。

它大致会做这些事：

- 记录容器启动时间。
- 标记容器进入 active 状态。
- 初始化或校验 `Environment`。
- 准备早期事件集合。
- 给后续刷新流程做基础状态检查。

例如配置里的这些信息，后面都要通过 `Environment` 体系读取：

```properties
spring.profiles.active=dev
server.port=8080
```

如果你是 Spring Boot 应用，Boot 会在调用 `refresh()` 前做更多环境准备，比如解析命令行参数、配置文件、profile、监听器等。但进入 Spring 核心容器之后，主线仍然会走到 `refresh()`。

### 3.3 `obtainFreshBeanFactory()`：拿到真正干活的 BeanFactory

这一步的核心是获取一个可用的 `BeanFactory`。

在 Spring 源码里，最常见的核心实现是：

```text
DefaultListableBeanFactory
```

它很关键，因为它既是：

- `BeanDefinitionRegistry`：可以注册 `BeanDefinition`
- `BeanFactory`：可以获取 Bean
- `AutowireCapableBeanFactory`：可以做自动装配
- `SingletonBeanRegistry`：可以管理单例缓存

如果是 XML 配置，类似：

```xml
<bean id="userService" class="com.demo.UserService"/>
```

会被解析成一个 `BeanDefinition`，放入 `BeanFactory`。

如果是注解配置，类似：

```java
@Component
public class UserService {
}
```

也会被扫描、解析，然后注册成 `BeanDefinition`。

注意，此时通常只是有了 Bean 的“图纸”，不是已经创建好了 `UserService` 对象。

### 3.4 `prepareBeanFactory()`：给 BeanFactory 装基础能力

拿到 `BeanFactory` 后，Spring 会给它设置一些基础组件和默认规则。

常见工作包括：

- 设置 `ClassLoader`
- 设置表达式解析器
- 注册属性编辑器或类型转换相关能力
- 注册容器内置 Bean
- 设置部分 Aware 接口的支持
- 注册一些默认依赖

比如业务 Bean 里可以注入这些对象：

```java
@Autowired
private ApplicationContext applicationContext;

@Autowired
private Environment environment;
```

不是因为你自己定义了它们，而是 Spring 在容器启动时把这些基础对象注册进去了。

如果 Bean 实现了下面这些接口，Spring 后续也知道怎么回调：

```java
BeanNameAware
BeanFactoryAware
ApplicationContextAware
EnvironmentAware
ResourceLoaderAware
```

这一步可以理解成：

> BeanFactory 已经拿到了，现在给它补上创建 Bean 时需要用的默认能力。

### 3.5 `postProcessBeanFactory()`：留给子类扩展

这是一个模板方法。

`AbstractApplicationContext` 在这里留了一个扩展点，允许不同类型的 `ApplicationContext` 在真正执行后置处理器前，先对 `BeanFactory` 做一点自己的定制。

普通应用里你可能不太关注它，但 Web 容器、特殊上下文、框架扩展可能会在这里做额外处理。

### 3.6 `invokeBeanFactoryPostProcessors()`：先改图纸，再造对象

这是启动流程里非常关键的一步。

`BeanFactoryPostProcessor` 操作的是：

```text
BeanDefinition
```

也就是 Bean 的元数据，而不是 Bean 实例。

它执行时，大多数普通 Bean 还没有创建。这样做的意义是：在真正创建对象之前，先允许框架或业务代码修改“对象怎么创建”。

例如：

```java
@Component
public class DemoBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        BeanDefinition bd = beanFactory.getBeanDefinition("userService");
        bd.setLazyInit(true);
    }
}
```

这个例子不是修改 `userService` 对象，因为此时对象可能还没创建。它修改的是 `userService` 的 `BeanDefinition`。

这一阶段里有一个极其重要的处理器：

```text
ConfigurationClassPostProcessor
```

它负责处理很多注解配置，例如：

- `@Configuration`
- `@Bean`
- `@ComponentScan`
- `@Import`
- `@PropertySource`
- `@Conditional`

比如：

```java
@Configuration
@ComponentScan("com.demo")
public class AppConfig {

    @Bean
    public UserService userService() {
        return new UserService();
    }
}
```

`ConfigurationClassPostProcessor` 会解析这个配置类，把扫描到的组件、`@Bean` 方法、导入的配置等都变成 `BeanDefinition` 注册进容器。

这也是为什么 Spring 要先处理 `BeanDefinition`：

> 很多 Bean 一开始并不在容器里，是在配置类解析、扫描、自动配置过程中被继续注册进去的。

### 3.7 `registerBeanPostProcessors()`：注册 Bean 创建过程中的拦截器

`BeanPostProcessor` 操作的是：

```text
Bean 实例
```

它会参与 Bean 的创建过程，尤其是初始化前后。

常见方法是：

```java
postProcessBeforeInitialization()
postProcessAfterInitialization()
```

Spring 很多核心能力都依赖 `BeanPostProcessor`：

- `@Autowired`
- `@Resource`
- `@Value`
- `@PostConstruct`
- AOP 代理
- 事务代理
- `@Async`
- `@Scheduled`

例如处理 `@Autowired` 的关键处理器是：

```text
AutowiredAnnotationBeanPostProcessor
```

处理 `@PostConstruct` 的相关处理器常见是：

```text
CommonAnnotationBeanPostProcessor
```

AOP 自动代理相关的处理器常见是：

```text
AnnotationAwareAspectJAutoProxyCreator
```

注意这一步只是“注册处理器”，不是已经给所有 Bean 做完增强。

这些处理器真正发挥作用，是后面创建 Bean 的时候。

### 3.8 `initMessageSource()`：初始化国际化组件

这一步处理国际化能力。

如果项目里配置了：

```text
messages_zh_CN.properties
messages_en_US.properties
```

Spring 可以通过 `MessageSource` 根据不同语言环境获取不同文案。

业务里可能这样使用：

```java
messageSource.getMessage("user.name", null, locale);
```

这不是 Bean 创建主线里最难的部分，但它体现了 `ApplicationContext` 比 `BeanFactory` 更完整：`BeanFactory` 主要管 Bean，`ApplicationContext` 还管国际化、事件、资源等应用级能力。

### 3.9 `initApplicationEventMulticaster()`：初始化事件广播器

Spring 有自己的事件机制。

你可以发布事件：

```java
applicationContext.publishEvent(new UserCreatedEvent(userId));
```

也可以监听事件：

```java
@EventListener
public void onUserCreated(UserCreatedEvent event) {
}
```

事件广播器的作用就是：

> 当一个事件被发布时，找到合适的监听器并调用它们。

这一阶段会准备好事件广播能力，后面 `registerListeners()` 和 `finishRefresh()` 都会用到。

### 3.10 `onRefresh()`：子类刷新扩展，Web 场景很重要

`onRefresh()` 也是模板方法。

普通 Spring 应用里，它可能没有太多存在感。

但 Spring Boot Web 应用里非常重要，因为内嵌 Web 服务器通常会在刷新过程中被创建和启动，比如：

- Tomcat
- Jetty
- Undertow

所以 Spring Boot 启动 Web 应用时，你看到 Tomcat 启动日志，本质上也是 `ApplicationContext#refresh()` 主线的一部分。

### 3.11 `registerListeners()`：注册事件监听器

这一步会把事件监听器注册到事件广播器里。

监听器来源可能有两类：

- 实现了 `ApplicationListener` 的 Bean
- 使用了 `@EventListener` 的方法

例如：

```java
@Component
public class UserEventListener {

    @EventListener
    public void handle(UserCreatedEvent event) {
    }
}
```

容器启动后，如果有人发布 `UserCreatedEvent`，Spring 就能找到这个监听方法。

### 3.12 `finishBeanFactoryInitialization()`：开始创建非懒加载单例 Bean

这是整个启动流程里最容易被问到的一步。

前面大量工作都是准备：

- 准备环境
- 准备 `BeanFactory`
- 注册 `BeanDefinition`
- 修改 `BeanDefinition`
- 注册 `BeanPostProcessor`
- 准备事件和国际化

到了这里，Spring 才开始批量创建非懒加载的单例 Bean。

核心调用链是：

```text
AbstractApplicationContext#finishBeanFactoryInitialization
  -> DefaultListableBeanFactory#preInstantiateSingletons
  -> AbstractBeanFactory#getBean
  -> AbstractBeanFactory#doGetBean
  -> AbstractAutowireCapableBeanFactory#createBean
  -> AbstractAutowireCapableBeanFactory#doCreateBean
```

比如：

```java
@Service
public class UserService {
}
```

如果它是默认单例，并且没有配置懒加载，那么一般会在容器启动阶段创建。

如果加了：

```java
@Lazy
@Service
public class UserService {
}
```

那它通常不会在启动时创建，而是在第一次被用到时创建。

这一阶段会触发完整的 Bean 生命周期：

```text
实例化 -> 属性填充 -> 初始化 -> 可能生成代理 -> 放入单例缓存
```

所以第三部分讲的是容器启动主线，第四部分讲的就是这一步里面“一个 Bean 具体怎么被创建出来”。

### 3.13 `finishRefresh()`：发布启动完成事件

最后一步主要做收尾：

- 清理一些临时缓存。
- 初始化生命周期处理器。
- 发布 `ContextRefreshedEvent`。
- 标记容器刷新完成。

如果你注册了监听器：

```java
@EventListener
public void onRefresh(ContextRefreshedEvent event) {
}
```

它会在容器刷新完成时被触发。

### 3.14 Spring Boot 启动和 Spring 启动是什么关系

Spring Boot 的入口通常是：

```java
SpringApplication.run(Application.class, args);
```

它做的事情比普通 Spring 更多，比如：

- 创建 `SpringApplication`
- 推断应用类型，是普通应用、Servlet Web 应用还是 Reactive Web 应用
- 加载 `ApplicationContextInitializer`
- 加载 `ApplicationListener`
- 准备 `Environment`
- 解析配置文件和命令行参数
- 创建合适的 `ApplicationContext`
- 加载自动配置
- 调用 `ApplicationContext#refresh()`
- 执行 `ApplicationRunner` 和 `CommandLineRunner`

但从核心容器角度看，关键仍然是：

```text
SpringApplication.run()
  -> 创建 ApplicationContext
  -> ApplicationContext#refresh()
```

`@SpringBootApplication` 本质上组合了：

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
```

其中 `@EnableAutoConfiguration` 会根据 classpath、配置文件、条件注解自动注册很多 `BeanDefinition`。

所以可以这样理解：

> Spring Boot 是在 Spring 容器启动主线外面包了一层自动化启动逻辑，真正创建和管理 Bean 的核心仍然是 Spring 容器。

### 3.15 为什么要先处理 `BeanDefinition`

因为 Spring 不是一上来就 new 对象，而是先收集“对象怎么创建”的信息。

这样做的好处是：

- 可以统一处理 XML、注解、Java Config、自动配置。
- 可以让框架在实例化前修改 Bean 的定义。
- 可以支持条件装配，比如 `@Conditional`。
- 可以支持作用域、懒加载、初始化方法、销毁方法等元信息。
- 可以先把依赖关系和创建规则整理好，再进入对象创建阶段。

一句话：

> `BeanDefinition` 是 Spring 创建 Bean 前的准备数据，Spring 先改图纸，再照图纸造对象。

### 3.16 容器启动流程面试回答模板

如果面试让你讲 Spring 启动流程，可以这样答：

> Spring 容器启动核心是 `AbstractApplicationContext#refresh()`。它先准备环境，然后创建或刷新 `BeanFactory`，把 XML、注解、配置类等解析成 `BeanDefinition`。接着执行 `BeanFactoryPostProcessor`，比如 `ConfigurationClassPostProcessor` 会解析 `@Configuration`、`@ComponentScan`、`@Bean`。然后注册 `BeanPostProcessor`，这些处理器会在后续 Bean 创建时处理依赖注入、初始化回调、AOP 代理等。之后 Spring 初始化事件、国际化等组件，最后通过 `finishBeanFactoryInitialization()` 预实例化非懒加载单例 Bean。所有 Bean 创建完成后，发布 `ContextRefreshedEvent`，容器启动完成。

### 3.17 这几个方法建议先眼熟

```text
AbstractApplicationContext#refresh
AbstractApplicationContext#prepareRefresh
AbstractApplicationContext#obtainFreshBeanFactory
AbstractApplicationContext#prepareBeanFactory
AbstractApplicationContext#invokeBeanFactoryPostProcessors
AbstractApplicationContext#registerBeanPostProcessors
AbstractApplicationContext#finishBeanFactoryInitialization
DefaultListableBeanFactory#preInstantiateSingletons
AbstractBeanFactory#doGetBean
AbstractAutowireCapableBeanFactory#doCreateBean
```

如果只想先追主线，先断：

```text
AbstractApplicationContext#refresh
DefaultListableBeanFactory#preInstantiateSingletons
AbstractAutowireCapableBeanFactory#doCreateBean
```

这样可以看到从容器刷新到 Bean 创建的完整跳转。

---

## 四、Bean 创建与生命周期

![Bean 生命周期](images/ssm-03-bean-lifecycle.svg)

第三部分说的是容器怎么启动，第四部分要看的是：容器启动到 `finishBeanFactoryInitialization()` 后，一个普通 Bean 到底怎么被创建出来。

Bean 的生命周期可以先粗略记成：

```text
实例化 -> 属性填充 -> Aware 回调 -> 初始化前处理 -> 初始化 -> 初始化后处理 -> 可用 -> 销毁
```

但这只是背诵版。源码里更完整的主线是：

```text
BeanDefinition
  -> getBean()
  -> doGetBean()
  -> createBean()
  -> doCreateBean()
  -> createBeanInstance()
  -> addSingletonFactory()
  -> populateBean()
  -> initializeBean()
  -> registerDisposableBeanIfNecessary()
  -> addSingleton()
```

可以理解成：

> Spring 先有 Bean 的图纸，再根据图纸实例化对象，提前暴露单例引用以处理部分循环依赖，然后注入属性，执行初始化回调和后置处理器，必要时生成代理对象，最后放入单例池。

### 4.1 创建 Bean 的关键方法

```text
AbstractBeanFactory#doGetBean
AbstractAutowireCapableBeanFactory#createBean
AbstractAutowireCapableBeanFactory#doCreateBean
AbstractAutowireCapableBeanFactory#createBeanInstance
AbstractAutowireCapableBeanFactory#populateBean
AbstractAutowireCapableBeanFactory#initializeBean
```

这些方法可以先这样理解：

- `doGetBean()`：我要这个 Bean。
- `createBean()`：如果没有，就创建它。
- `doCreateBean()`：真正执行创建流程。
- `createBeanInstance()`：先把对象造出来。
- `populateBean()`：把依赖注入进去。
- `initializeBean()`：做 Aware 回调、初始化前后处理、初始化方法。

更细一点的调用链是：

```text
DefaultListableBeanFactory#preInstantiateSingletons
  -> AbstractBeanFactory#getBean
  -> AbstractBeanFactory#doGetBean
  -> DefaultSingletonBeanRegistry#getSingleton
  -> AbstractAutowireCapableBeanFactory#createBean
  -> AbstractAutowireCapableBeanFactory#doCreateBean
  -> AbstractAutowireCapableBeanFactory#createBeanInstance
  -> AbstractAutowireCapableBeanFactory#populateBean
  -> AbstractAutowireCapableBeanFactory#initializeBean
```

### 4.2 第一步：从 `BeanDefinition` 开始

Bean 生命周期不是从 `new UserService()` 才开始，而是从 `BeanDefinition` 开始。

例如：

```java
@Service
public class UserService {
}
```

Spring 会先把它解析成 `BeanDefinition`。里面记录了：

- Bean 的类名
- Bean 的作用域
- 是否懒加载
- 构造器参数
- 属性依赖
- 初始化方法
- 销毁方法
- 是否需要自动装配

所以 `BeanDefinition` 可以理解成 Bean 的施工图。

后面创建 Bean 的时候，Spring 不是凭空猜，而是根据这张图纸决定：

- 用哪个构造器
- 注入哪些属性
- 是否单例
- 是否需要初始化
- 是否需要销毁回调

### 4.3 第二步：`getBean()` 触发创建

Bean 创建通常由 `getBean()` 触发。

如果是非懒加载单例 Bean，容器启动阶段会在这里触发：

```text
DefaultListableBeanFactory#preInstantiateSingletons()
```

如果是懒加载 Bean，则可能等到业务代码第一次获取时触发：

```java
applicationContext.getBean(UserService.class);
```

进入 `doGetBean()` 后，Spring 大致会做这些判断：

1. 先查单例缓存，看有没有已经创建好的 Bean。
2. 如果有，直接返回。
3. 如果没有，检查当前 BeanDefinition。
4. 如果依赖其他 Bean，先创建依赖。
5. 根据 scope 决定创建单例、原型或其他作用域对象。
6. 调用 `createBean()` 真正创建。

### 4.4 第三步：实例化 Bean

实例化对应：

```text
createBeanInstance()
```

这一步只是把对象造出来，还没有完成依赖注入。

常见实例化方式有三种。

第一种，默认构造器：

```java
@Service
public class UserService {
    public UserService() {
    }
}
```

第二种，带参构造器：

```java
@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
}
```

第三种，工厂方法：

```java
@Bean
public UserService userService() {
    return new UserService();
}
```

这一步结束后，内存里已经有了对象，但它可能还是“半成品”。

比如字段注入还没有完成：

```java
@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
}
```

`UserService` 对象可以已经 new 出来了，但 `userRepository` 此时还没有注入。

### 4.5 第四步：提前暴露单例引用，处理部分循环依赖

对于单例 Bean，Spring 在实例化后、属性填充前，会考虑提前暴露对象引用。

这和三级缓存有关：

| 缓存 | 含义 |
| --- | --- |
| `singletonObjects` | 一级缓存，完整初始化好的单例 Bean |
| `earlySingletonObjects` | 二级缓存，提前暴露的早期 Bean 引用 |
| `singletonFactories` | 三级缓存，生成早期引用的 `ObjectFactory` |

典型循环依赖：

```java
@Service
public class A {
    @Autowired
    private B b;
}

@Service
public class B {
    @Autowired
    private A a;
}
```

大致过程是：

1. 创建 A，先实例化 A。
2. A 还没注入属性，Spring 先把 A 的早期引用工厂放入三级缓存。
3. A 填充属性时发现需要 B，于是去创建 B。
4. 创建 B，B 填充属性时发现需要 A。
5. 此时 A 还没有完全初始化，但可以从三级缓存拿到 A 的早期引用。
6. B 注入 A，B 初始化完成。
7. A 继续注入 B，A 初始化完成。
8. A、B 最终进入一级缓存。

这就是为什么 Spring 能解决一部分循环依赖。

但它通常解决不了构造器循环依赖：

```java
@Service
public class A {
    public A(B b) {
    }
}

@Service
public class B {
    public B(A a) {
    }
}
```

原因很简单：A 构造时必须先有 B，B 构造时又必须先有 A。对象连实例化都没完成，就没有机会提前暴露引用。

所以面试时可以这样说：

> Spring 主要通过单例三级缓存解决 setter 或字段注入的部分循环依赖，构造器循环依赖和 prototype 循环依赖通常解决不了。

### 4.6 第五步：属性填充，也就是依赖注入

属性填充对应：

```text
populateBean()
```

这一步才是真正把依赖塞进去。

它会处理：

- 字段注入
- setter 注入
- `@Autowired`
- `@Resource`
- `@Value`
- XML property 注入

例如：

```java
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Value("${server.port}")
    private Integer port;
}
```

Spring 会解析注入点，然后去容器里找合适的 Bean 或配置值。

`@Autowired` 的底层处理器通常是：

```text
AutowiredAnnotationBeanPostProcessor
```

它会参与属性填充过程，找到字段、方法、构造器上的 `@Autowired`，再通过容器解析依赖。

常见依赖解析链路是：

```text
AutowiredAnnotationBeanPostProcessor
  -> BeanFactory#resolveDependency
  -> DefaultListableBeanFactory#doResolveDependency
```

如果同类型 Bean 有多个，Spring 会继续根据这些规则选择：

- `@Qualifier`
- `@Primary`
- Bean 名称
- 泛型信息
- 是否唯一

如果还是无法确定，就会报常见的依赖注入异常。

### 4.7 第六步：执行 Aware 回调

属性注入完成后，会进入初始化阶段。初始化之前，Spring 会先处理一部分 Aware 接口。

常见接口有：

```java
BeanNameAware
BeanFactoryAware
ApplicationContextAware
EnvironmentAware
ResourceLoaderAware
```

例如：

```java
@Component
public class UserService implements BeanNameAware, ApplicationContextAware {

    @Override
    public void setBeanName(String name) {
        System.out.println("beanName = " + name);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        System.out.println("拿到 ApplicationContext");
    }
}
```

这一步的作用是：

> 如果业务 Bean 想感知容器信息，Spring 会在初始化前把相关对象回调给它。

实际开发里不要滥用 Aware 接口，因为它会让业务代码更依赖 Spring 容器。但看源码和面试时要知道它在生命周期里的位置。

### 4.8 第七步：初始化前的 `BeanPostProcessor`

接下来会执行：

```java
BeanPostProcessor#postProcessBeforeInitialization
```

示例：

```java
@Component
public class DemoBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        System.out.println("初始化前：" + beanName);
        return bean;
    }
}
```

这一阶段经常和 `@PostConstruct` 联系在一起。

严格说，`@PostConstruct` 是由相关的 `BeanPostProcessor` 识别并调用的，所以它属于初始化前后置处理这一段逻辑里的重要动作。

例如：

```java
@Component
public class UserService {

    @PostConstruct
    public void init() {
        System.out.println("初始化");
    }
}
```

### 4.9 第八步：执行初始化方法

初始化方法常见有三种。

第一种，`@PostConstruct`：

```java
@PostConstruct
public void init() {
}
```

第二种，实现 `InitializingBean`：

```java
@Component
public class UserService implements InitializingBean {

    @Override
    public void afterPropertiesSet() {
    }
}
```

第三种，配置 `initMethod`：

```java
@Bean(initMethod = "init")
public UserService userService() {
    return new UserService();
}
```

常见执行顺序可以这样记：

```text
@PostConstruct
  -> InitializingBean#afterPropertiesSet()
  -> 自定义 initMethod
```

为什么 `@PostConstruct` 会排在前面？因为它是在初始化前的后置处理器里被调用的，后面才轮到 `afterPropertiesSet()` 和自定义初始化方法。

### 4.10 第九步：初始化后的 `BeanPostProcessor`，AOP 代理常在这里出现

初始化方法执行完后，会调用：

```java
BeanPostProcessor#postProcessAfterInitialization
```

这一步非常重要，因为很多代理对象会在这里生成。

比如：

```java
@Service
public class UserService {

    @Transactional
    public void createUser() {
    }
}
```

如果这个类需要事务增强，Spring 最终放进容器里的可能不是原始 `UserService` 对象，而是一个代理对象。

常见代理来源包括：

- `@Transactional`
- `@Cacheable`
- `@Async`
- 自定义 AOP 切面

AOP 相关的后置处理器会判断当前 Bean 是否需要代理。如果需要，就返回代理对象；如果不需要，就返回原始对象。

所以要注意这句话：

> Bean 生命周期里创建出来的原始对象，不一定就是最终暴露给业务使用的对象，容器里保存的可能是代理对象。

### 4.11 第十步：注册销毁逻辑并放入单例池

如果 Bean 是单例，初始化完成后会放入一级缓存：

```text
singletonObjects
```

后续再获取这个 Bean，就不会重新创建，而是直接从单例缓存中返回。

如果 Bean 有销毁方法，Spring 也会注册销毁逻辑，等容器关闭时调用。

常见销毁方式有三种。

第一种，`@PreDestroy`：

```java
@PreDestroy
public void destroy() {
}
```

第二种，实现 `DisposableBean`：

```java
@Component
public class UserService implements DisposableBean {

    @Override
    public void destroy() {
    }
}
```

第三种，配置 `destroyMethod`：

```java
@Bean(destroyMethod = "close")
public DataSource dataSource() {
    return new HikariDataSource();
}
```

常见销毁顺序可以这样记：

```text
@PreDestroy
  -> DisposableBean#destroy()
  -> 自定义 destroyMethod
```

容器关闭时，例如：

```java
applicationContext.close();
```

Spring 会触发这些销毁回调。

### 4.12 一个最常见的生命周期顺序

```text
解析配置，生成 BeanDefinition
  -> BeanFactoryPostProcessor 修改 BeanDefinition
  -> getBean() 触发创建
  -> 实例化 Bean
  -> 提前暴露单例早期引用
  -> 属性填充，完成依赖注入
  -> Aware 接口回调
  -> BeanPostProcessor#postProcessBeforeInitialization
  -> @PostConstruct
  -> InitializingBean#afterPropertiesSet
  -> 自定义 initMethod
  -> BeanPostProcessor#postProcessAfterInitialization
  -> 必要时生成 AOP 代理
  -> 放入单例池，Bean 可用
  -> 容器关闭
  -> @PreDestroy
  -> DisposableBean#destroy
  -> 自定义 destroyMethod
```

### 4.13 `BeanFactoryPostProcessor` vs `BeanPostProcessor`

这个题几乎必问。

| 扩展点 | 作用对象 | 作用时机 | 典型用途 |
| --- | --- | --- | --- |
| `BeanFactoryPostProcessor` | `BeanDefinition` | Bean 实例化前 | 改配置、改作用域、改懒加载 |
| `BeanPostProcessor` | Bean 实例 | Bean 创建前后 | AOP 代理、依赖注入增强、对象包装 |

一句话：

> 前者改“图纸”，后者改“成品”。

再具体一点：

- `BeanFactoryPostProcessor`：Bean 实例化之前执行，能改 `BeanDefinition`。
- `BeanPostProcessor`：Bean 实例化之后执行，能改 Bean 实例，甚至返回代理对象。

如果面试官继续追问，可以补一句：

> `ConfigurationClassPostProcessor` 是很重要的 `BeanFactoryPostProcessor`，负责解析配置类；`AutowiredAnnotationBeanPostProcessor`、`CommonAnnotationBeanPostProcessor`、AOP 自动代理处理器都是常见的 `BeanPostProcessor`。

### 4.14 `FactoryBean` 在生命周期里怎么理解

`FactoryBean` 本身也是一个 Bean，但它比较特殊。

普通 Bean：

```text
getBean("userService") -> 返回 UserService 对象
```

`FactoryBean`：

```text
getBean("sqlSessionFactory") -> 返回 FactoryBean 生产出来的对象
getBean("&sqlSessionFactory") -> 返回 FactoryBean 本身
```

MyBatis 和 Spring 整合里常见的：

```text
SqlSessionFactoryBean
MapperFactoryBean
```

就利用了这个机制。

所以 `FactoryBean` 可以这样理解：

> 它是一个受 Spring 管理的工厂 Bean，生命周期由 Spring 管，但正常获取时拿到的是它生产的产品对象。

### 4.15 Bean 生命周期面试回答模板

如果面试让你讲 Bean 生命周期，可以这样答：

> Spring 创建 Bean 前，先根据配置、注解或自动配置生成 `BeanDefinition`。真正创建时，通过 `getBean()` 进入 `doGetBean()`，如果单例缓存里没有，就调用 `createBean()` 和 `doCreateBean()`。先通过构造器或工厂方法实例化对象，然后为了处理部分循环依赖，单例 Bean 会提前暴露早期引用。接着执行 `populateBean()` 完成属性填充，也就是依赖注入。然后进入初始化阶段，先执行 Aware 接口回调，再执行 `BeanPostProcessor` 的初始化前方法，其中 `@PostConstruct` 通常也在这个阶段被调用。然后执行 `InitializingBean#afterPropertiesSet()` 和自定义 `initMethod`。初始化完成后执行 `BeanPostProcessor` 的初始化后方法，AOP、事务等代理对象通常在这里生成。最后 Bean 放入单例池供业务使用，容器关闭时再执行 `@PreDestroy`、`DisposableBean#destroy()` 和自定义销毁方法。

---

## 五、IoC、依赖注入与 BeanDefinition

![Spring Bean 创建流程](images/ssm-04-circular-dependency.svg)

### 5.1 IoC 到底解决了什么

没有 IoC 时，对象之间自己 `new`，依赖关系散在各处。  
有了 IoC 之后，对象的创建和依赖关系交给容器统一管理。

你可以把 Spring 看成一个“对象关系调度中心”：

- 对象什么时候创建，由容器决定。
- 对象依赖谁，由容器注入。
- 对象是否单例、是否懒加载，由图纸决定。

### 5.2 `@Autowired` 底层是怎么找依赖的

常见主线是：

```text
AutowiredAnnotationBeanPostProcessor
  -> resolveDependency()
  -> DefaultListableBeanFactory#doResolveDependency
```

它做的事情本质上就是：

1. 看注入点要什么类型。
2. 去容器里找候选 Bean。
3. 根据 `@Primary`、`@Qualifier`、名称、类型等规则选出一个。
4. 注入进去。

### 5.3 `BeanDefinition` 和注入有什么关系

`BeanDefinition` 不只是记录类名，它还记录：

- 这个 Bean 依赖什么
- 由哪个构造器创建
- 是否延迟初始化
- 是否是单例
- 是否需要走自动装配

所以你可以这样理解：

> Spring 先根据 `BeanDefinition` 知道“这个对象要怎么造”，再在创建过程中完成依赖注入。

### 5.4 多实现类为什么会冲突

当容器里有多个同类型 Bean 时，Spring 需要做决策：

- `@Primary` 优先
- `@Qualifier` 精确指定
- 按名字匹配
- 实在不行就报错

这也是面试时经常追问“为什么注入失败”的原因。

---

## 六、循环依赖与三级缓存

### 6.1 哪些情况能解决，哪些不行

能解决的常见场景：

- 单例 Bean
- setter 注入
- 字段注入

通常解决不了的场景：

- 构造器注入循环依赖
- prototype 作用域循环依赖
- 过早触发代理失败的复杂场景

### 6.2 三级缓存是什么

| 缓存 | 存什么 |
| --- | --- |
| `singletonObjects` | 完整初始化好的单例 |
| `earlySingletonObjects` | 早期曝光的对象引用 |
| `singletonFactories` | 生成早期引用的 `ObjectFactory` |

### 6.3 为什么不是二级缓存

因为 Spring 有时候不能太早决定暴露的是“原始对象”还是“代理对象”。  
三级缓存里放的是一个工厂，等真正有人来取早期引用时，再决定给谁。

### 6.4 `ObjectFactory` 为什么重要

它的作用就是“延迟决定”：

- 先把创建早期引用的能力放进去。
- 等真正需要时再拿。
- 这样可以兼容 AOP 代理和循环依赖场景。

### 6.5 一句话回答

> Spring 通过单例三级缓存和早期曝光机制解决部分循环依赖，核心是先暴露一个可延迟生成早期引用的 `ObjectFactory`，等后置处理器和代理逻辑准备好后，再决定对外暴露原始对象还是代理对象。

---

## 七、AOP 与声明式事务

![AOP 代理链](images/ssm-05-aop-proxy.svg)
![事务执行链](images/ssm-06-transaction-flow.svg)

### 7.1 AOP 是什么

一句话：

> 在不改业务代码的前提下，把日志、权限、审计、事务等横切逻辑织入方法调用过程。

### 7.2 JDK 动态代理和 CGLIB

| 方式 | 代理对象 | 适合场景 |
| --- | --- | --- |
| JDK 动态代理 | 接口代理 | 有接口的 Bean |
| CGLIB | 继承目标类生成子类 | 没有接口或需要强制代理类 |

### 7.3 AOP 调用链主线

```text
外部调用代理对象
  -> 拦截器链
  -> 目标方法
  -> 返回结果 / 异常处理
```

### 7.4 `@Transactional` 为什么本质上也是 AOP

因为它也是方法拦截：

1. 进入代理。
2. `TransactionInterceptor` 开启或加入事务。
3. 从数据源拿连接并绑定到当前线程。
4. 执行业务方法。
5. 正常提交，异常回滚。

### 7.5 事务为什么能共用同一个连接

关键在于：

- 连接被绑定到了当前线程。
- 后续同线程内的 JDBC/MyBatis 操作复用这个连接。

你可以直接记：

> Spring 事务靠的是 `TransactionSynchronizationManager` 这类线程绑定机制，不是“自动帮你新开一个事务对象”那么简单。

### 7.6 事务失效最常见的几个坑

1. 自调用。
2. 非 `public` 方法。
3. 异常被吞掉。
4. 异步线程切换。
5. 传播行为理解错误。

### 7.7 面试一句话版

> Spring 声明式事务本质是 AOP，核心是代理拦截、线程绑定连接、提交回滚控制。最常见失效原因是自调用、异常被吞、非 public 方法和线程切换。

---

## 八、Spring MVC 请求处理链

![Spring MVC 请求链路](images/ssm-07-mvc-request.svg)
![参数绑定](images/ssm-08-argument-binding.svg)
![异常处理](images/ssm-09-exception-flow.svg)

### 8.1 一次请求的大链路

```text
DispatcherServlet
  -> HandlerMapping 找到处理器
  -> HandlerAdapter 适配并调用
  -> 参数解析器完成入参绑定
  -> Controller 执行业务方法
  -> 返回值处理器处理结果
  -> 消息转换器写回 JSON / 视图
  -> 异常处理器统一兜底
```

### 8.2 为什么要有 HandlerAdapter

因为 Controller 形式不止一种：

- `@RequestMapping`
- `@ResponseBody`
- 参数注解绑定
- 返回值类型多样

`HandlerAdapter` 的作用就是把“不同风格的 Handler”统一成可执行流程。

### 8.3 参数为什么能自动绑定

核心是 `HandlerMethodArgumentResolver`。

它会根据：

- 参数类型
- 注解类型
- 请求体内容
- URL 参数

决定如何构造方法入参。

### 8.4 返回值为什么能自动转 JSON

核心是 `HandlerMethodReturnValueHandler` 和消息转换器。  
如果方法上有 `@ResponseBody`，Spring 会把对象交给合适的 `HttpMessageConverter` 输出成 JSON。

### 8.5 异常为什么能统一处理

常见入口是：

- `@ControllerAdvice`
- `@ExceptionHandler`
- `HandlerExceptionResolver`

这让你可以把 Controller 里的 try-catch 收敛到统一异常体系。

---

## 九、MyBatis 核心执行链

![MyBatis 执行链路](images/ssm-10-mybatis-execution.svg)

这张图讲的是：你平时调用的 `userMapper.selectById(id)`，并不是接口自己在执行 SQL，而是被 MyBatis 转成了一条完整的执行链。

先从 Mapper 接口开始。Mapper 接口没有实现类，Spring 注入进来的实际是 `MapperProxy` 动态代理对象。你一调用接口方法，代理就会根据“接口全限定名 + 方法名”找到对应的 `MappedStatement`，也就是 XML 或注解里那条 SQL 的元数据。

然后进入 `SqlSession`。`SqlSession` 可以理解成 MyBatis 对外暴露的会话入口，但它自己不真正干重活，真正调度 SQL 执行的是 `Executor`。`Executor` 会处理一级缓存、事务协同、查询或更新的分发，再把具体 JDBC 执行动作交给 `StatementHandler`。

`StatementHandler` 负责创建和执行 JDBC 的 `Statement` 或 `PreparedStatement`。在真正发给数据库前，`ParameterHandler` 会把 `#{}` 里的参数设置进去；数据库返回结果后，`ResultSetHandler` 再把结果集映射成 Java 对象。`TypeHandler` 穿插在参数设置和结果映射里，负责 Java 类型和 JDBC 类型之间的转换。

所以这张图最该记住的一句话是：**Mapper 方法调用只是入口，底层会经过代理定位 SQL，再由 `SqlSession`、`Executor` 和各类 Handler 配合完成参数绑定、SQL 执行和结果映射。**

把它和你每天写的代码对应起来，就是下面这条线：

```java
User user = userMapper.selectById(1L);
```

```xml
<select id="selectById" resultType="User">
    select id, username from sys_user where id = #{id}
</select>
```

`selectById` 这个方法名会定位到 `MappedStatement`，`#{id}` 会交给 `ParameterHandler` 做预编译参数绑定，查询结果里的 `id`、`username` 会交给 `ResultSetHandler` 映射回 `User` 对象。你平时看到的“调用 Mapper 得到对象”，中间其实已经走完了一套 SQL 元数据定位、参数处理、JDBC 执行、结果映射流程。

![MyBatis 插件机制](images/ssm-11-mybatis-plugin.svg)

这张图讲的是：MyBatis 插件不是在任意地方“插一脚”，它只能围绕四类核心对象做拦截：`Executor`、`StatementHandler`、`ParameterHandler`、`ResultSetHandler`。

插件本质上还是动态代理。MyBatis 创建这些核心对象后，会用插件链一层层包起来；真正执行方法时，调用会先进入插件的 `intercept` 方法，插件可以在执行前后做增强，也可以修改 SQL、参数或结果，最后再通过 `invocation.proceed()` 放行到原始逻辑。

你日常接触最多的是分页插件和多租户插件。分页插件常拦截 `Executor` 或 `StatementHandler`，在 SQL 发给数据库前追加 `limit`，必要时再生成一条 `count` SQL；多租户、数据权限插件则可能在 SQL 上追加 `tenant_id` 或权限条件。慢 SQL 统计、审计字段处理也可以基于类似机制做。

这里有个容易忽略的点：插件是有顺序的。多个插件都改 SQL 时，谁先包裹、谁先执行，会影响最终 SQL 的样子。面试或排查问题时，不要只说“用了插件”，还要能说清楚“拦截了哪个对象、在 SQL 执行前还是执行后做了什么”。

常见场景可以这样对应：

| 场景 | 常见拦截点 | 做了什么 |
| --- | --- | --- |
| 分页 | `Executor` / `StatementHandler` | 改写 SQL，追加分页条件，可能额外执行 `count` |
| 多租户 | `StatementHandler` | 给 SQL 自动追加 `tenant_id` 条件 |
| 数据权限 | `StatementHandler` | 按当前用户、角色、部门追加过滤条件 |
| 慢 SQL 统计 | `Executor` | 记录执行前后时间、SQL、参数 |
| 结果脱敏 | `ResultSetHandler` | 在结果映射后对字段做处理 |

所以 MyBatis-Plus 的分页、租户、数据权限能力，本质上不是绕开 MyBatis 另起炉灶，而是站在 MyBatis 插件机制上，对 SQL 执行链路做增强。

![MyBatis 一级二级缓存](images/ssm-12-mybatis-cache.svg)

这张图讲的是 MyBatis 自带缓存的范围：一级缓存跟着 `SqlSession` 走，二级缓存跟着 Mapper 的 namespace 走。

一级缓存默认开启，作用范围是同一个 `SqlSession`。同一个会话里，完全相同的查询如果已经查过一次，后面可能直接从本地缓存拿结果，不再访问数据库。只要发生 `insert`、`update`、`delete`、`commit`、`rollback` 等操作，一级缓存通常会被清掉，避免同一个会话里继续读到明显过期的数据。

在 Spring 整合 MyBatis 后，你通常不会手写 `SqlSession`，而是通过 `SqlSessionTemplate` 调 Mapper。没有事务时，一次 Mapper 调用通常很快结束；有事务时，同一事务线程内的数据库操作会围绕 Spring 绑定的连接和会话资源协同，所以更要知道一级缓存可能在同一会话范围内生效。

二级缓存是 namespace 级别，多个 `SqlSession` 可以共享，但默认需要显式配置。它的问题是业务一致性更难控制：一个 Mapper 的缓存不一定知道另一个 Mapper 或另一张表发生了更新，分布式部署下也更复杂。所以实际项目里，一级缓存自然使用，二级缓存通常很谨慎；更常见的做法是把业务缓存交给 Redis、Caffeine 这类外部缓存，并明确设计 key、过期时间和失效策略。

所以这张图最该记住的是：**一级缓存是会话内的小缓存，默认存在；二级缓存是 namespace 级别的共享缓存，理论上能减少查询，工程上却容易带来脏数据风险。**

如果排查“为什么同一段代码查出来的数据不是我预期的”，可以从三个问题入手：

1. 是不是同一个 `SqlSession` 里重复查询，命中了一级缓存？
2. 中间有没有执行写操作，导致缓存被清空？
3. 有没有开启二级缓存，多个 Mapper 或多表更新是否会让缓存失效不及时？

如果业务特别在意每次查询都打到数据库，可以了解 `localCacheScope=STATEMENT`，它会把一级缓存范围缩小到单次语句执行。但大多数项目不需要一上来就改这个配置，先理解默认行为，比盲目关缓存更重要。

### 9.1 Mapper 为什么能直接调用

Mapper 接口并不是普通对象，而是动态代理。

```text
MapperProxy#invoke
  -> MapperMethod#execute
  -> SqlSession
  -> Executor
  -> StatementHandler
  -> ParameterHandler
  -> ResultSetHandler
```

### 9.2 `SqlSession`、`Executor`、`MappedStatement`

| 对象 | 作用 |
| --- | --- |
| `SqlSession` | 对外操作入口 |
| `Executor` | 执行调度、缓存、事务协同 |
| `MappedStatement` | 一条 SQL 的元数据描述 |
| `StatementHandler` | JDBC Statement 执行 |
| `ParameterHandler` | 参数绑定 |
| `ResultSetHandler` | 结果映射 |

### 9.3 `#{}` 和 `${}` 的区别

| 写法 | 含义 | 风险 |
| --- | --- | --- |
| `#{}` | 预编译参数 | 安全，推荐 |
| `${}` | 字符串拼接 | SQL 注入风险高 |

### 9.4 插件和缓存最该记住什么

- 插件本质是拦截 MyBatis 四大对象。
- 一级缓存是 `SqlSession` 级别。
- 二级缓存是 namespace 级别。
- 写操作会清缓存。

---

## 十、Spring 与 MyBatis 怎么整合

![Spring 与 MyBatis 整合](images/ssm-13-spring-mybatis.svg)

### 10.1 Mapper 为什么能变成 Spring Bean

常见主线是：

```text
@MapperScan
  -> 注册 MapperDefinition
  -> MapperFactoryBean
  -> 生成 MapperProxy
```

也就是说，Mapper 接口并不是直接 `new` 出来的，而是交给 Spring + MyBatis 的集成体系处理。

### 10.2 `SqlSessionTemplate` 为什么重要

`SqlSessionTemplate` 的价值是把 MyBatis 的 `SqlSession` 使用方式托管给 Spring。

它的作用包括：

- 让 SQL 执行参与 Spring 事务。
- 统一连接获取和释放。
- 让 Mapper 调用更安全。

### 10.3 为什么 Spring 事务能管住 MyBatis

因为 Spring 在事务开启时会把连接资源绑定到当前线程，而 MyBatis 执行 SQL 时会从这套线程资源里拿连接。  
所以同一个事务内，Spring 和 MyBatis 能协同工作。

### 10.4 一个常见面试回答

> Mapper 接口之所以能直接调用，是因为 Spring 通过 `MapperFactoryBean` 把接口注册成 Bean，运行时实际返回的是 `MapperProxy` 动态代理。SQL 执行交给 `SqlSession` 和 `Executor`，而 `SqlSessionTemplate` 让 MyBatis 的执行过程接入 Spring 事务管理。

---

## 十一、面试高频题速答

### 11.1 什么是 IoC

> IoC 就是把对象的创建和依赖关系交给容器统一管理，业务代码只关心使用，不关心怎么 new 和怎么组装。

### 11.2 `BeanDefinition` 是什么

> 它是 Bean 的元数据描述，像图纸一样记录类名、作用域、依赖、初始化方法等信息，Spring 先处理它，再创建对象。

### 11.3 `BeanFactoryPostProcessor` 和 `BeanPostProcessor` 区别

> 前者操作 `BeanDefinition`，后者操作 Bean 实例。前者在实例化前执行，后者在 Bean 创建前后执行。

### 11.4 Spring 如何解决循环依赖

> 通过单例三级缓存和早期曝光机制解决 setter/字段注入的部分循环依赖，构造器循环依赖通常解决不了。

### 11.5 AOP 底层原理

> 基于 JDK 动态代理或 CGLIB 生成代理对象，调用时按拦截器链增强目标方法。

### 11.6 为什么事务本质上也是 AOP

> 因为 `@Transactional` 也是方法拦截，代理里会开启事务、绑定连接、决定提交回滚。

### 11.7 事务为什么会失效

> 常见原因是自调用、异常被吞、非 public、异步线程切换、传播行为理解错。

### 11.8 Spring MVC 请求怎么走

> `DispatcherServlet` 先通过 `HandlerMapping` 找到处理器，再通过 `HandlerAdapter` 执行，参数解析器负责入参绑定，返回值处理器和消息转换器负责输出。

### 11.9 Mapper 为什么能执行 SQL

> 因为 Mapper 是动态代理对象。方法调用会进入 `MapperProxy#invoke`，再转成 `MapperMethod` 执行语义，最后交给 `SqlSession` 和 `Executor`。

### 11.10 `#{}` 和 `${}` 区别

> `#{}` 是预编译参数，安全；`${}` 是字符串拼接，灵活但容易 SQL 注入。

### 11.11 `BeanFactory` 和 `ApplicationContext` 区别

> `BeanFactory` 更底层，主要负责取 Bean；`ApplicationContext` 是更完整的容器，在此基础上加了国际化、事件、资源加载等能力。

### 11.12 Spring 容器启动流程怎么讲

> 核心入口是 `AbstractApplicationContext#refresh()`。它先准备环境，再创建或刷新 `BeanFactory`，把配置解析成 `BeanDefinition`。然后执行 `BeanFactoryPostProcessor` 修改 Bean 定义，注册 `BeanPostProcessor` 参与后续 Bean 创建。接着初始化国际化、事件广播器、监听器等组件，最后通过 `finishBeanFactoryInitialization()` 预实例化非懒加载单例 Bean，并在 `finishRefresh()` 发布容器刷新完成事件。

### 11.13 Bean 生命周期完整顺序

> 先解析配置生成 `BeanDefinition`，然后 `getBean()` 触发创建。创建时先实例化对象，再提前暴露单例早期引用以解决部分循环依赖，然后属性填充，执行 Aware 回调，执行初始化前 `BeanPostProcessor`，调用 `@PostConstruct`、`InitializingBean#afterPropertiesSet()`、自定义 `initMethod`，再执行初始化后 `BeanPostProcessor`。如果需要 AOP，通常在初始化后返回代理对象。容器关闭时再执行 `@PreDestroy`、`DisposableBean#destroy()` 和自定义 `destroyMethod`。

### 11.14 `@PostConstruct`、`InitializingBean`、`initMethod` 谁先执行

> 常见顺序是 `@PostConstruct` 先执行，然后是 `InitializingBean#afterPropertiesSet()`，最后是自定义 `initMethod`。因为 `@PostConstruct` 是由相关 `BeanPostProcessor` 在初始化前阶段识别并调用的。

### 11.15 Spring Boot 启动和 Spring 启动有什么关系

> Spring Boot 先准备 `SpringApplication`、环境、监听器、配置文件、自动配置和合适的 `ApplicationContext`，然后仍然会进入 `ApplicationContext#refresh()`。所以 Boot 是在 Spring 容器启动主线外面包了一层自动化配置和启动逻辑，Bean 的创建和管理核心仍然是 Spring。

---

## 十二、推荐复习路线和断点位

### 12.1 如果你只想快速过一遍

建议顺序：

1. 二、先认识关键类
2. 三、Spring 容器启动主线
3. 四、Bean 创建与生命周期
4. 六、循环依赖与三级缓存
5. 七、AOP 与声明式事务
6. 八、Spring MVC 请求处理链
7. 九、MyBatis 核心执行链
8. 十、Spring 与 MyBatis 怎么整合
9. 十一、高频题速答

### 12.2 最值得打断点的地方

| 模块 | 建议断点 |
| --- | --- |
| Spring 容器 | `AbstractApplicationContext#refresh`、`prepareRefresh`、`invokeBeanFactoryPostProcessors`、`finishBeanFactoryInitialization` |
| Bean 创建 | `DefaultListableBeanFactory#preInstantiateSingletons`、`AbstractBeanFactory#doGetBean`、`AbstractAutowireCapableBeanFactory#doCreateBean` |
| 循环依赖 | `AbstractAutowireCapableBeanFactory#doCreateBean`、`getSingleton` |
| AOP | `AbstractAutoProxyCreator`、`createProxy` |
| 事务 | `TransactionInterceptor#invoke` |
| MVC | `DispatcherServlet#doDispatch` |
| MyBatis | `MapperProxy#invoke`、`MapperMethod#execute` |

### 12.3 三天冲刺版

**第 1 天**：Spring 容器、BeanDefinition、生命周期、循环依赖。  
**第 2 天**：AOP、事务、MVC 请求链。  
**第 3 天**：MyBatis、Spring 整合、速答背诵。

### 12.4 最后建议

如果你时间真的不多，就先把下面这条线背顺：

```text
BeanDefinition -> refresh() -> Bean 创建 -> 三级缓存 -> AOP 代理 -> 事务拦截 -> MVC 调度 -> Mapper 动态代理
```

这条线顺了，SSM 面试基本就不会再是“背答案”，而是能讲机制、讲链路、讲排查。
