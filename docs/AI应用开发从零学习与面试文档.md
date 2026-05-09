# AI 应用开发从零学习与面试文档

> 面向没有系统开发过 AI 技术栈的 Java 工程师。目标是让你先知道 AI 应用到底怎么用、怎么接、怎么落地，再理解 Prompt、Embedding、RAG、函数调用、成本、稳定性和面试高频点。

![AI 应用调用链路](images/ai-01-llm-app-flow.svg)

## 目录

- [一、先用一个例子看懂 AI 应用](#一先用一个例子看懂-ai-应用)
- [二、AI 应用到底是什么](#二ai-应用到底是什么)
- [三、大模型的几个基础概念](#三大模型的几个基础概念)
- [四、Prompt 怎么写才像工程能力](#四prompt-怎么写才像工程能力)
- [五、Embedding 和向量检索](#五embedding-和向量检索)
- [六、RAG：企业知识库问答核心模式](#六rag企业知识库问答核心模式)
- [七、工具调用与函数调用](#七工具调用与函数调用)
- [八、Java 项目里怎么接 AI](#八java-项目里怎么接-ai)
- [九、生产级 AI 应用要考虑什么](#九生产级-ai-应用要考虑什么)
- [十、学习路线和实操任务](#十学习路线和实操任务)
- [十一、面试高频回答模板](#十一面试高频回答模板)

---

## 一、先用一个例子看懂 AI 应用

假设你做一个“订单客服助手”。用户问：

```text
我的订单 202605090001 为什么还没发货？
```

传统系统只能按按钮查订单。AI 应用可以做成：

1. 理解用户问题。
2. 识别订单号。
3. 调用订单查询接口。
4. 结合物流规则和订单状态生成自然语言回复。

### 1.1 Java 伪代码

```java
@RestController
@RequestMapping("/ai/customer-service")
public class CustomerServiceAiController {

    private final OrderService orderService;
    private final LlmClient llmClient;

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String orderNo = extractOrderNo(request.message());
        OrderDTO order = orderService.getByOrderNo(orderNo);

        String prompt = """
                你是电商平台客服助手。
                请根据订单信息回答用户问题，不要编造不存在的信息。

                用户问题：
                %s

                订单信息：
                订单号：%s
                状态：%s
                支付时间：%s
                发货时间：%s
                """.formatted(
                request.message(),
                order.orderNo(),
                order.status(),
                order.payTime(),
                order.deliveryTime()
        );

        String answer = llmClient.chat(prompt);
        return new ChatResponse(answer);
    }

    private String extractOrderNo(String message) {
        Pattern pattern = Pattern.compile("\\d{12}");
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            throw new BusinessException("未识别到订单号");
        }
        return matcher.group();
    }
}
```

### 1.2 这个例子说明什么

AI 应用不是“把问题直接扔给模型”这么简单。  
真正有价值的 AI 应用通常是：

```text
用户问题
  -> 业务系统提取上下文
  -> 必要时查库/调接口/检索知识库
  -> 组装 Prompt
  -> 调模型
  -> 后处理和审计
  -> 返回结果
```

对 Java 工程师来说，AI 应用的核心工作不是训练大模型，而是：

- 把模型接进业务系统
- 给模型可靠上下文
- 控制模型输出边界
- 接好权限、日志、审计、成本和稳定性

---

## 二、AI 应用到底是什么

### 2.1 先区分几个概念

| 概念 | 你可以这样理解 |
| --- | --- |
| 大模型 LLM | 一个能根据上下文预测并生成文本的模型 |
| Prompt | 你给模型的指令和上下文 |
| Token | 模型处理文本的基本单位 |
| Embedding | 把文本转换成向量 |
| RAG | 检索知识后再让模型回答 |
| Agent | 能规划步骤并调用工具完成任务的系统 |

### 2.2 AI 应用和普通应用的区别

普通应用：

```text
输入固定 -> 规则固定 -> 输出确定
```

AI 应用：

```text
输入开放 -> 上下文动态 -> 输出概率化
```

所以 AI 应用更需要：

- 输出校验
- 权限控制
- 结果兜底
- 可观测性
- 人工审核边界

### 2.3 Java 工程师要学到什么程度

你不一定要会训练模型，但至少要懂：

1. 怎么调用模型 API
2. 怎么写稳定 Prompt
3. 怎么做知识库问答 RAG
4. 怎么让模型调用业务工具
5. 怎么控制成本、延迟和安全

---

## 三、大模型的几个基础概念

### 3.1 Token

Token 可以粗略理解成模型处理文本的片段。  
中文、英文、标点都会被切成 token。

为什么重要？

- 模型上下文长度按 token 算
- 调用费用通常按 token 算
- 响应延迟和 token 数相关

### 3.2 上下文窗口

模型一次能“看见”的最大 token 数叫上下文窗口。

这决定了：

- 能塞多少历史对话
- 能塞多少文档片段
- 是否需要摘要或裁剪

### 3.3 Temperature

控制输出随机性。

| 场景 | 建议 |
| --- | --- |
| 客服、SQL 生成、严肃问答 | 低一点 |
| 文案、创意、头脑风暴 | 高一点 |

### 3.4 幻觉

幻觉就是模型生成了看似合理但不真实的内容。

降低幻觉的核心方法：

1. 给明确上下文
2. 要求不知道就说不知道
3. 用 RAG 提供资料
4. 对关键输出做程序校验
5. 不让模型直接决定高风险动作

---

## 四、Prompt 怎么写才像工程能力

### 4.1 Prompt 的基本结构

推荐结构：

```text
角色
任务
上下文
约束
输出格式
示例
```

### 4.2 一个更稳的 Prompt 示例

```text
你是电商平台客服助手。

任务：
根据订单信息回答用户问题。

约束：
1. 只能基于给定订单信息回答。
2. 如果信息不足，请说“当前信息不足，需要人工客服进一步确认”。
3. 不要承诺退款、赔偿或补发。
4. 输出控制在 120 字以内。

订单信息：
{order_context}

用户问题：
{user_question}
```

### 4.3 Prompt 工程的本质

不是“写得玄学”，而是：

- 控制角色
- 控制输入
- 控制输出格式
- 控制边界
- 给模型足够上下文

### 4.4 常见错误

1. 指令太泛
2. 没有输出格式
3. 没有限制模型编造
4. 把敏感权限完全交给模型
5. 不记录 prompt 和响应，无法排查

---

## 五、Embedding 和向量检索

### 5.1 Embedding 是什么

Embedding 是把文本转成一串数字向量。

相似文本在向量空间里距离更近。

例如：

```text
“订单多久发货”
“我的商品什么时候发出”
```

这两句话字面不同，但语义接近，向量也会接近。

### 5.2 向量检索解决什么

传统关键词搜索依赖字面匹配。  
向量检索更适合语义相似。

适合：

- 知识库问答
- 相似问题推荐
- 文档检索
- 商品语义召回

### 5.3 Java 侧基本流程

```java
public List<DocumentChunk> searchKnowledge(String question) {
    float[] queryVector = embeddingClient.embed(question);
    return vectorStore.search(queryVector, 5);
}
```

---

## 六、RAG：企业知识库问答核心模式

RAG，Retrieval-Augmented Generation，检索增强生成。

### 6.1 为什么需要 RAG

因为大模型不知道你公司的内部文档、订单规则、项目代码、业务流程。  
你需要先检索相关资料，再让模型基于资料回答。

### 6.2 RAG 基本链路

```text
文档上传
  -> 文档解析
  -> 切分 chunk
  -> 生成 embedding
  -> 写入向量库

用户提问
  -> 问题 embedding
  -> 向量检索 topK
  -> 拼接上下文
  -> 调用模型回答
```

### 6.3 文档切分为什么重要

切太大：

- 检索不准
- 浪费上下文

切太小：

- 语义不完整

常见策略：

- 按标题切
- 按段落切
- 固定长度 + overlap

### 6.4 RAG Prompt 示例

```text
你是企业内部知识库助手。

请只基于【参考资料】回答问题。
如果参考资料中没有答案，请回答：“资料中未找到相关信息”。

【参考资料】
{retrieved_chunks}

【用户问题】
{question}
```

### 6.5 RAG 常见坑

1. 文档切分太粗或太碎
2. 检索 topK 不合理
3. 没有重排
4. 把无关资料塞给模型
5. 模型回答没有引用来源

---

## 七、工具调用与函数调用

### 7.1 什么是工具调用

模型自己不会查你的数据库。  
但你可以告诉它有哪些工具：

- 查询订单
- 查询物流
- 创建工单
- 查询库存

模型判断需要哪个工具，然后应用服务执行。

### 7.2 一个简化例子

```java
public ToolResult queryOrderStatus(String orderNo) {
    OrderDTO order = orderService.getByOrderNo(orderNo);
    return new ToolResult(Map.of(
            "orderNo", order.orderNo(),
            "status", order.status(),
            "deliveryTime", order.deliveryTime()
    ));
}
```

### 7.3 工具调用的边界

高风险动作不能让模型直接执行，比如：

- 退款
- 删除数据
- 转账
- 修改权限

这类操作至少要：

- 二次确认
- 权限校验
- 审计日志
- 人工审核

---

## 八、Java 项目里怎么接 AI

### 8.1 最小可行架构

```text
Controller
  -> AI Application Service
  -> Prompt Builder
  -> LLM Client
  -> Audit Log
```

### 8.2 LLM Client 封装示例

```java
public interface LlmClient {
    String chat(String prompt);
}
```

```java
@Component
public class HttpLlmClient implements LlmClient {

    private final WebClient webClient;

    @Override
    public String chat(String prompt) {
        LlmRequest request = new LlmRequest("gpt-4.1-mini", prompt);
        LlmResponse response = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(LlmResponse.class)
                .block(Duration.ofSeconds(30));
        return response.content();
    }
}
```

实际项目里要补：

- 超时
- 重试
- 熔断
- 限流
- 审计
- 成本统计

---

## 九、生产级 AI 应用要考虑什么

### 9.1 成本

关注：

- 输入 token
- 输出 token
- 重试次数
- RAG 检索片段数量
- 是否需要缓存

### 9.2 延迟

AI 调用通常比普通接口慢。  
优化方式：

- 流式输出
- 控制上下文长度
- 使用小模型处理简单任务
- 缓存固定问答

### 9.3 安全

关注：

- Prompt 注入
- 数据越权
- 敏感信息泄露
- 模型输出不可控

### 9.4 可观测性

至少记录：

- 用户问题
- prompt 版本
- 检索片段 ID
- 模型名
- token 用量
- 响应时间
- 异常信息

---

## 十、学习路线和实操任务

### 第 1 天：会调用模型

目标：

- 写一个 Controller
- 接收用户问题
- 调模型返回答案

### 第 2 天：学 Prompt

目标：

- 写 3 个 Prompt
- 分别用于客服、总结、分类

### 第 3 天：做 RAG

目标：

- 准备 5 篇 Markdown 文档
- 切分
- 生成 embedding
- 检索 topK
- 拼上下文回答

### 第 4 天：做工具调用

目标：

- 让模型识别订单号
- 调订单接口
- 再生成回答

### 第 5 天：加工程能力

目标：

- 加日志
- 加超时
- 加成本统计
- 加敏感词检查

---

## 十一、面试高频回答模板

### 11.1 AI 应用怎么落地

> 我会把 AI 应用拆成模型调用、Prompt 构造、上下文检索、工具调用、结果校验和审计监控几层。模型负责生成，业务系统负责提供可靠上下文、权限控制和结果兜底。

### 11.2 RAG 是什么

> RAG 是检索增强生成。先把企业文档切分并向量化，用户提问时先做向量检索，拿到相关片段后再拼进 Prompt 让模型回答。它解决的是模型不知道企业私有知识、容易幻觉的问题。

### 11.3 怎么降低幻觉

> 通过明确 Prompt 约束、提供检索上下文、要求信息不足时不要编造、对结构化输出做程序校验，以及对高风险动作加人工确认和权限控制。

### 11.4 AI 应用和普通业务系统最大区别

> 普通系统输出更确定，AI 应用输出是概率化的，所以更需要边界控制、结果校验、日志审计、成本控制和降级方案。

---

## 最后建议

你作为 Java 工程师学习 AI，第一阶段不要纠结训练模型。  
先把这条线跑通：

> 用户问题 -> 构造 Prompt -> 调模型 -> 加上下文 -> RAG 检索 -> 工具调用 -> 日志审计。

这条线跑通后，你就能开始把 AI 能力真正接到业务系统里。
