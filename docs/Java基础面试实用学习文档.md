# Java 基础面试实用学习文档

> 适合 3-5 年 Java 工程师巩固基础。目标不是背零散 API，而是把面向对象、集合、泛型、异常、I/O、反射、注解、序列化、Java 8+ 新特性和常见代码坑讲清楚，能自然接到 JVM、并发、Spring 和项目实践。

## 目录

- [一、Java 基础面试主线](#一java-基础面试主线)
- [二、面向对象与基础语法](#二面向对象与基础语法)
- [三、String、包装类与常见不可变对象](#三string包装类与常见不可变对象)
- [四、集合框架高频问题](#四集合框架高频问题)
- [五、泛型、枚举与注解](#五泛型枚举与注解)
- [六、异常体系与工程实践](#六异常体系与工程实践)
- [七、I/O、NIO 与文件处理](#七ionio-与文件处理)
- [八、反射、动态代理与 SPI](#八反射动态代理与-spi)
- [九、序列化、JSON 与对象拷贝](#九序列化json-与对象拷贝)
- [十、Java 8 到 Java 17 常见新特性](#十java-8-到-java-17-常见新特性)
- [十一、常见代码题与坑点](#十一常见代码题与坑点)
- [十二、面试高频回答模板](#十二面试高频回答模板)

---

## 一、Java 基础面试主线

Java 基础不是“简单题”，它通常是面试官判断你工程功底的入口：

```text
面向对象
  -> equals/hashCode/String/包装类
  -> 集合源码和使用场景
  -> 泛型/异常/反射/注解
  -> I/O/NIO/序列化
  -> Java 8+ 新特性
  -> 项目里怎么避坑
```

4 年经验要避免只回答定义。比如问 `HashMap`，不要只说“数组 + 链表 + 红黑树”，更好的回答是：

> `HashMap` 用哈希桶存储键值对，JDK 8 后桶内冲突过多会从链表转成红黑树，降低极端情况下的查询复杂度。工程上我更关注 key 的 `equals/hashCode` 是否稳定、初始化容量是否合理、是否会被并发访问，以及是否需要有序、线程安全或淘汰策略。如果涉及并发，我不会直接用 `HashMap`，而是用 `ConcurrentHashMap` 或在更高层控制并发边界。

---

## 二、面向对象与基础语法

### 2.1 面向对象三大特性怎么讲

| 特性 | 含义 | 项目里的体现 |
| --- | --- | --- |
| 封装 | 隐藏内部状态，通过方法暴露行为 | DTO/VO/Entity 分层，Service 封装业务规则 |
| 继承 | 复用父类能力，表达 is-a 关系 | 基础实体类、通用 Controller、模板方法 |
| 多态 | 父类型引用指向子类对象，运行期决定具体行为 | 策略模式、接口注入、Spring Bean 按实现替换 |

面试回答要补一句：**继承不是复用的唯一方式，组合通常比继承更灵活**。业务系统里滥用继承容易造成父类膨胀、层级过深和改动扩散。

### 2.2 重载和重写

| 对比项 | 重载 Overload | 重写 Override |
| --- | --- | --- |
| 发生位置 | 同一个类或父子类 | 父子类 |
| 判断依据 | 方法名相同，参数列表不同 | 方法签名相同 |
| 返回值 | 不能只靠返回值区分重载 | 返回值可协变 |
| 绑定时期 | 编译期决定 | 运行期动态分派 |

常见坑：

```java
void test(Object o) {
    System.out.println("object");
}

void test(String s) {
    System.out.println("string");
}

test(null); // string，因为编译器选择更具体的重载
```

### 2.3 接口和抽象类怎么选

接口表达“能力契约”，抽象类表达“部分实现 + 模板约束”。

| 场景 | 建议 |
| --- | --- |
| 多个不相关类具备同一种能力 | 接口 |
| 需要统一流程，子类只填少数步骤 | 抽象类 + 模板方法 |
| 希望支持多实现、方便 Spring 注入替换 | 接口 |
| 有稳定公共状态和公共方法 | 抽象类 |

Java 8 之后接口有 `default` 方法，但不要把接口写成工具类或半个基类。默认方法更适合兼容已有实现，或提供很轻的默认行为。

### 2.4 值传递还是引用传递

Java 只有值传递。对象作为参数传递时，传的是“对象引用的副本”。

```java
void change(User user) {
    user.setName("new"); // 能影响外部对象状态
    user = new User();   // 只改变当前方法里的引用副本
}
```

面试表达：

> Java 方法参数传递的是值。基本类型传具体值，对象类型传引用值的副本。所以方法内修改对象字段会影响同一个对象，但把参数变量重新指向新对象不会影响调用方引用。

---

## 三、String、包装类与常见不可变对象

### 3.1 String 为什么不可变

`String` 不可变的好处：

1. 可以安全放入字符串常量池复用。
2. 可以缓存 `hashCode`，适合作为 `HashMap` key。
3. 天然线程安全。
4. 避免路径、类名、URL、权限标识等字符串被中途篡改。

常见追问：

```java
String a = "abc";
String b = "a" + "bc";
String c = new String("abc");

a == b; // true，编译期常量折叠
a == c; // false，c 是堆上的新对象
a.equals(c); // true
```

### 3.2 StringBuilder 和 StringBuffer

| 类型 | 特点 | 场景 |
| --- | --- | --- |
| `String` | 不可变 | 少量拼接、常量、key |
| `StringBuilder` | 可变，非线程安全 | 方法内局部拼接，最常用 |
| `StringBuffer` | 可变，方法加锁 | 老代码或确实需要共享同步 |

注意：循环里大量字符串拼接要用 `StringBuilder`。但简单的 `a + b + c` 编译器会优化，不必过度紧张。

### 3.3 包装类缓存

```java
Integer a = 127;
Integer b = 127;
Integer c = 128;
Integer d = 128;

a == b; // true
c == d; // false，默认缓存 -128 到 127
```

工程建议：

1. 包装类比较值用 `Objects.equals(a, b)`。
2. DTO 入参尽量用包装类表达“未传”和“传了 0”的区别。
3. 自动拆箱可能触发 NPE。

```java
Integer count = null;
if (count > 0) { // NPE
}
```

### 3.4 BigDecimal 常见坑

金额计算不要用 `double`，用 `BigDecimal`。

```java
BigDecimal a = new BigDecimal("0.1");
BigDecimal b = BigDecimal.valueOf(0.1);
```

不要用 `new BigDecimal(0.1)`，因为二进制浮点已经不精确。金额比较通常用 `compareTo`：

```java
new BigDecimal("1.0").equals(new BigDecimal("1.00"));    // false
new BigDecimal("1.0").compareTo(new BigDecimal("1.00")); // 0
```

---

## 四、集合框架高频问题

### 4.1 集合体系总览

```text
Collection
  -> List: ArrayList, LinkedList
  -> Set: HashSet, LinkedHashSet, TreeSet
  -> Queue/Deque: ArrayDeque, PriorityQueue

Map
  -> HashMap, LinkedHashMap, TreeMap, ConcurrentHashMap
```

选择集合时先问四件事：

1. 是否允许重复。
2. 是否需要按下标访问。
3. 是否需要有序。
4. 是否有并发访问。

### 4.2 ArrayList 和 LinkedList

| 对比项 | ArrayList | LinkedList |
| --- | --- | --- |
| 底层结构 | 动态数组 | 双向链表 |
| 随机访问 | 快，O(1) | 慢，O(n) |
| 尾部追加 | 通常快，扩容时慢 | 快 |
| 中间插入删除 | 需要移动元素 | 找到节点后操作快 |
| 实际工程 | 更常用，缓存友好 | 队列/双端队列场景可考虑 |

很多人说 `LinkedList` 插入删除快，这是不完整的。中间插入前要先定位节点，定位本身是 O(n)，而且链表对 CPU 缓存不友好，业务系统里 `ArrayList` 通常更常用。

### 4.3 HashMap 底层原理

JDK 8 的 `HashMap` 核心结构：

```text
数组 table
  -> 桶位为空：直接放
  -> 桶位有元素：链表或红黑树
  -> 链表过长且数组足够大：树化
```

关键点：

1. 默认容量 16，负载因子 0.75。
2. 容量保持 2 的幂，方便用 `(n - 1) & hash` 定位桶。
3. 扩容会创建新数组，并重新分布节点。
4. JDK 8 后链表达到阈值并且数组容量足够时会树化。

常见追问：

**为什么容量是 2 的幂？**

> 为了用位运算快速取模，并让扩容后的元素只需要判断 hash 的某一位，就能决定留在原位置还是移动到新位置，降低 rehash 成本。

**为什么重写 equals 必须重写 hashCode？**

> 因为哈希集合先用 hash 定位桶，再用 equals 判断相等。如果两个对象 equals 相等但 hashCode 不同，可能落到不同桶里，导致 HashMap/HashSet 判断失败。

### 4.4 HashMap 为什么线程不安全

线程不安全体现在：

1. 并发 put 可能覆盖数据。
2. 扩容期间读写可能看到不一致状态。
3. 复合操作如 `containsKey` 后 `put` 不是原子操作。

多线程场景用 `ConcurrentHashMap`，或者在业务层通过锁、串行队列、数据库唯一约束等方式控制。

### 4.5 ConcurrentHashMap 简述

JDK 8 的 `ConcurrentHashMap` 主要依靠：

1. CAS 初始化和插入空桶。
2. 桶级别 `synchronized` 控制冲突桶写入。
3. `volatile` 保证节点数组和节点值的可见性。
4. 扩容时多个线程协助迁移。

回答要注意：JDK 8 已经不是 Segment 分段锁为主了，面试时可以提“JDK 7 是 Segment 思路，JDK 8 转为 CAS + synchronized + 链表/红黑树桶级同步”。

### 4.6 LinkedHashMap 和 LRU

`LinkedHashMap` 在 HashMap 基础上维护双向链表，可以保持插入顺序或访问顺序。实现简单 LRU 可以覆盖 `removeEldestEntry`：

```java
Map<String, Object> cache = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
        return size() > 1000;
    }
};
```

注意它不是线程安全缓存，生产缓存一般用 Caffeine、Redis 或业务已有缓存组件。

### 4.7 fail-fast 和 fail-safe

`ArrayList`、`HashMap` 迭代时如果结构被并发修改，可能抛 `ConcurrentModificationException`，这叫 fail-fast。

```java
for (String item : list) {
    if (needRemove(item)) {
        list.remove(item); // 可能抛异常
    }
}
```

正确方式：

```java
Iterator<String> iterator = list.iterator();
while (iterator.hasNext()) {
    if (needRemove(iterator.next())) {
        iterator.remove();
    }
}
```

---

## 五、泛型、枚举与注解

### 5.1 泛型解决什么问题

泛型把类型检查从运行期提前到编译期，减少强转和 `ClassCastException`。

```java
List<String> names = new ArrayList<>();
names.add("Tom");
String name = names.get(0);
```

### 5.2 类型擦除

Java 泛型主要通过类型擦除实现，运行期通常拿不到 `List<String>` 和 `List<Integer>` 的实际泛型差异。

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();

a.getClass() == b.getClass(); // true
```

常见影响：

1. 不能 `new T()`。
2. 不能创建泛型数组 `new List<String>[10]`。
3. 方法重载不能只靠泛型参数区分。
4. 反射获取字段、父类、方法签名上的泛型信息需要看 `Type`。

### 5.3 通配符 extends 和 super

记住 PECS 原则：

```text
Producer Extends, Consumer Super
```

如果集合是生产者，只读取，用 `? extends T`；如果集合是消费者，要写入，用 `? super T`。

```java
void read(List<? extends Number> list) {
    Number n = list.get(0);
}

void write(List<? super Integer> list) {
    list.add(1);
}
```

### 5.4 枚举为什么适合做状态

枚举优点：

1. 类型安全，避免魔法字符串。
2. 天然单例。
3. 可以带字段和行为。
4. `switch` 和序列化支持好。

```java
public enum OrderStatus {
    CREATED("created", "已创建"),
    PAID("paid", "已支付"),
    CANCELED("canceled", "已取消");

    private final String code;
    private final String desc;

    OrderStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
```

状态流转复杂时，不要只靠枚举硬写，可以配合状态机或流程引擎。

### 5.5 注解怎么生效

注解本身只是元数据，真正生效依赖处理器：

1. 编译期处理：Lombok、MapStruct。
2. 类加载或运行期反射：Spring 扫描注解创建 Bean。
3. AOP 拦截：`@Transactional`、权限注解、日志注解。

自定义注解时要关注：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    String value();
}
```

`RetentionPolicy.RUNTIME` 表示运行期可通过反射读取。没有处理逻辑的注解不会自动产生行为。

---

## 六、异常体系与工程实践

### 6.1 异常体系

```text
Throwable
  -> Error: JVM 或系统级严重问题，通常不捕获
  -> Exception
      -> Checked Exception: 编译期要求处理
      -> RuntimeException: 运行时异常
```

业务系统常见做法：

1. 参数错误、状态不允许、资源不存在等使用自定义业务异常。
2. Controller 层用全局异常处理统一返回。
3. 日志只在边界处记录，避免重复打印。
4. 不要吞异常。

### 6.2 finally 一定会执行吗

大多数情况下会执行，但不是绝对：

1. JVM 退出，如 `System.exit()`。
2. 进程被强杀。
3. 机器断电。
4. `try` 或 `catch` 中线程一直阻塞。

还有一个经典坑：

```java
int test() {
    try {
        return 1;
    } finally {
        return 2;
    }
}
```

最终返回 2。工程上不要在 `finally` 里 `return`，会覆盖异常或正常返回。

### 6.3 try-with-resources

实现 `AutoCloseable` 的资源建议用 try-with-resources：

```java
try (InputStream input = Files.newInputStream(path)) {
    return input.readAllBytes();
}
```

它能保证资源关闭，并且比手写 `finally` 更清晰。

### 6.4 事务和异常的关系

Spring 默认只对 `RuntimeException` 和 `Error` 回滚。Checked Exception 默认不回滚，除非配置：

```java
@Transactional(rollbackFor = Exception.class)
public void importData() throws IOException {
}
```

常见事务不回滚原因：

1. 异常被 catch 后没有继续抛出。
2. 抛的是 checked exception，未配置 `rollbackFor`。
3. 自调用绕过代理。
4. 方法不是通过 Spring Bean 调用。
5. 多线程里执行的逻辑不在原事务上下文。

---

## 七、I/O、NIO 与文件处理

### 7.1 BIO、NIO、AIO 怎么讲

| 模型 | 特点 | 常见场景 |
| --- | --- | --- |
| BIO | 阻塞 I/O，一个连接通常对应一个线程 | 简单文件读写、低并发 socket |
| NIO | 非阻塞、Selector 多路复用、Buffer/Channel | Netty、网关、高并发网络通信 |
| AIO | 异步 I/O，完成后回调 | Java 里使用相对少 |

后端面试里不一定要手写 NIO，但要能解释 Netty、Tomcat NIO、Gateway 这类组件为什么能支撑高并发连接。

### 7.2 字节流和字符流

| 类型 | 处理单位 | 代表 |
| --- | --- | --- |
| 字节流 | byte | `InputStream`、`OutputStream` |
| 字符流 | char | `Reader`、`Writer` |

文本处理要明确字符集：

```java
Files.readString(path, StandardCharsets.UTF_8);
Files.writeString(path, content, StandardCharsets.UTF_8);
```

乱码问题通常来自：文件编码、HTTP 编码、数据库连接编码、页面编码不一致。

### 7.3 大文件怎么处理

大文件不要一次性读入内存：

```java
try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
    String line;
    while ((line = reader.readLine()) != null) {
        handle(line);
    }
}
```

上传下载大文件要考虑：

1. 分片上传和断点续传。
2. 流式处理，避免 OOM。
3. 限制文件类型和大小。
4. 临时文件清理。
5. 对象存储预签名 URL 或后端转发的取舍。

---

## 八、反射、动态代理与 SPI

### 8.1 反射能做什么

反射允许运行期获取类信息、创建对象、访问字段、调用方法。

Spring、MyBatis、Jackson 等框架大量使用反射：

1. 扫描类和注解。
2. 创建 Bean。
3. 属性注入。
4. JSON 字段映射。
5. Mapper 接口代理。

代价：

1. 可读性和类型安全较差。
2. 性能通常弱于直接调用。
3. 可能破坏封装。
4. Java 模块化和原生镜像场景需要额外配置。

### 8.2 JDK 动态代理和 CGLIB

| 对比项 | JDK 动态代理 | CGLIB |
| --- | --- | --- |
| 基础 | 接口代理 | 子类代理 |
| 要求 | 目标类实现接口 | 类不能是 final，方法不能是 final |
| Spring 使用 | 有接口时常用 | 无接口时常用 |

Spring AOP 本质是在代理对象上织入增强逻辑，所以自调用会失效：

```java
public void outer() {
    inner(); // this.inner，不经过代理
}

@Transactional
public void inner() {
}
```

### 8.3 SPI 是什么

SPI 是一种服务发现机制。调用方定义接口，提供方在约定位置声明实现，运行时加载。

JDK SPI 示例：

```text
META-INF/services/com.example.PaymentProvider
```

常见应用：

1. JDBC Driver 自动加载。
2. Dubbo 扩展点。
3. 日志门面绑定。
4. 自定义插件化能力。

---

## 九、序列化、JSON 与对象拷贝

### 9.1 Java 原生序列化为什么慎用

`Serializable` 使用简单，但生产里常被 JSON、Protobuf、Kryo 等替代。原因：

1. 序列化结果体积偏大。
2. 跨语言不友好。
3. 版本兼容需要维护 `serialVersionUID`。
4. 反序列化历史上出现过安全风险。

### 9.2 JSON 序列化常见问题

1. 日期格式和时区。
2. Long 精度在前端丢失。
3. 循环引用。
4. 字段命名策略。
5. 敏感字段泄露。
6. 多态反序列化安全。

后端给前端返回大整数 ID 时，要关注前端 JavaScript 的安全整数范围，必要时转字符串。

### 9.3 深拷贝和浅拷贝

浅拷贝只复制对象本身，引用字段仍指向同一个对象。深拷贝会复制引用字段指向的对象。

```java
User copied = new User();
copied.setName(origin.getName());
copied.setAddress(origin.getAddress()); // 这是浅拷贝引用字段
```

工程建议：

1. DTO 转换不要滥用反射 BeanUtils 处理复杂对象。
2. 嵌套对象、集合、时间、枚举要明确转换规则。
3. 关键链路可以用 MapStruct 这类编译期映射工具。

---

## 十、Java 8 到 Java 17 常见新特性

### 10.1 Lambda 和函数式接口

函数式接口只有一个抽象方法：

```java
@FunctionalInterface
public interface Converter<S, T> {
    T convert(S source);
}
```

Lambda 适合表达短小行为，不适合塞复杂业务逻辑。复杂逻辑应提取成命名方法，方便调试和复用。

### 10.2 Stream 使用边界

适合：

1. 集合过滤、映射、分组、聚合。
2. 声明式数据处理。
3. 中等规模内存集合。

不适合：

1. 复杂副作用。
2. 需要频繁 break/continue 的流程。
3. 超大数据集一次性载入内存。
4. 需要清晰异常处理的 I/O 操作。

```java
Map<Long, List<Order>> ordersByUser = orders.stream()
    .filter(order -> order.getStatus() == OrderStatus.PAID)
    .collect(Collectors.groupingBy(Order::getUserId));
```

### 10.3 Optional 正确用法

`Optional` 适合作为返回值表达可能为空，不建议作为实体字段或方法参数滥用。

```java
return userRepository.findById(id)
    .map(User::getName)
    .orElse("unknown");
```

不要这样写：

```java
if (optional.isPresent()) {
    return optional.get();
}
```

这基本退回了 null 判断。

### 10.4 CompletableFuture

适合并行编排多个独立 I/O：

```java
CompletableFuture<User> userFuture = CompletableFuture.supplyAsync(() -> getUser(userId), executor);
CompletableFuture<List<Order>> orderFuture = CompletableFuture.supplyAsync(() -> getOrders(userId), executor);

return userFuture.thenCombine(orderFuture, UserOrderVO::new).join();
```

注意点：

1. 不要默认使用公共线程池跑阻塞 I/O。
2. 要设置超时和异常兜底。
3. 注意线程上下文，如登录用户、TraceId、事务上下文不会自动传递。

### 10.5 Java 17 常见特性

项目使用 JDK 17 时，常见可关注：

| 特性 | 价值 |
| --- | --- |
| `var` 局部变量推断 | 减少局部变量冗余，但不要牺牲可读性 |
| 文本块 | 写 SQL、JSON、多行文本更清晰 |
| switch 表达式 | 状态分支更简洁 |
| record | 适合不可变数据载体 |
| sealed class | 限定继承层级，适合明确边界的模型 |

示例：

```java
String type = switch (status) {
    case CREATED -> "new";
    case PAID -> "done";
    case CANCELED -> "closed";
};
```

业务系统使用新特性要考虑团队熟悉度、框架兼容性和代码规范，不是越新越好。

---

## 十一、常见代码题与坑点

### 11.1 equals 和 hashCode

```java
class User {
    private Long id;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User user)) {
            return false;
        }
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
```

注意：如果对象放入 HashSet/HashMap 后，参与 hash 的字段被修改，就可能再也找不到这个对象。

### 11.2 Arrays.asList 坑

```java
List<String> list = Arrays.asList("a", "b");
list.add("c"); // UnsupportedOperationException
```

它返回的是固定长度列表。需要可变列表时：

```java
List<String> list = new ArrayList<>(Arrays.asList("a", "b"));
```

### 11.3 subList 坑

`subList` 返回原列表视图，不是独立新列表。原列表结构变化可能影响子列表，甚至抛异常。

```java
List<String> sub = new ArrayList<>(list.subList(0, 10));
```

需要长期保存时，复制成新列表。

### 11.4 Stream toMap 重复 key

```java
Map<Long, User> map = users.stream()
    .collect(Collectors.toMap(User::getId, Function.identity()));
```

如果 id 重复会抛异常。应指定合并策略：

```java
Map<Long, User> map = users.stream()
    .collect(Collectors.toMap(User::getId, Function.identity(), (oldValue, newValue) -> newValue));
```

### 11.5 SimpleDateFormat 线程不安全

`SimpleDateFormat` 不是线程安全的。现代 Java 建议用 `DateTimeFormatter`：

```java
private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
```

### 11.6 removeIf

集合删除满足条件的元素可以用：

```java
list.removeIf(item -> item.getExpireTime().isBefore(now));
```

比 for-each 里 remove 更安全清晰。

---

## 十二、面试高频回答模板

### 12.1 HashMap 为什么高频

> `HashMap` 是 Java 集合里最典型的数据结构题。它底层是数组加桶内链表或红黑树，通过 key 的 hash 定位数组下标，再通过 equals 判断 key 是否相同。JDK 8 后，冲突链表过长会树化，降低极端冲突下的查询成本。工程上我会关注 key 的 hashCode 是否稳定、初始化容量是否合理、是否需要有序、是否有并发访问。如果有并发写入，就不能直接使用 HashMap，应考虑 ConcurrentHashMap 或业务层加锁。

### 12.2 ArrayList 和 LinkedList 怎么选

> 大多数业务场景我优先选 ArrayList，因为它随机访问快、内存连续、CPU 缓存友好，尾部追加也很高效。LinkedList 理论上节点插入删除快，但如果要先按下标定位，定位本身就是 O(n)，而且对象节点分散，实际性能未必好。只有明确需要队列、双端队列或频繁操作头尾节点时，才考虑 LinkedList 或 ArrayDeque。

### 12.3 你怎么理解泛型擦除

> Java 泛型主要是编译期类型检查，运行期大部分泛型信息会被擦除，所以 `List<String>` 和 `List<Integer>` 运行期 class 是一样的。它的好处是兼容老版本字节码，代价是不能直接 `new T()`，不能创建泛型数组，重载也不能只靠泛型参数区分。如果框架要拿泛型类型，通常要从字段、方法返回值或父类签名里的 `Type` 信息解析。

### 12.4 业务异常怎么设计

> 我一般会把业务异常设计成运行时异常，包含错误码、错误消息和必要上下文。Controller 层通过全局异常处理器统一转换成 API 响应。Service 内部遇到业务不满足条件时抛业务异常，让事务按规则回滚。日志不在每一层重复打印，而是在入口边界或全局异常处理处统一记录，避免日志噪音。

### 12.5 Java 17 对项目有什么价值

> Java 17 是长期支持版本，除了性能和 GC 改进，也带来一些语言层面的可读性提升，比如文本块、switch 表达式、record 等。业务系统里我会谨慎使用这些特性：SQL 或 JSON 多行文本可以用文本块，简单不可变返回结构可以用 record，但核心领域对象仍要看框架兼容和团队规范。

