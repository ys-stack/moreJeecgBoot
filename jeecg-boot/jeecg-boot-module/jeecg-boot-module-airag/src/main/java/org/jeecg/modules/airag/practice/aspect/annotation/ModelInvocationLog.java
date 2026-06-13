package org.jeecg.modules.airag.practice.aspect.annotation;

import java.lang.annotation.*;

/**
 * 模型调用日志注解
 * 加在调用大模型的方法上，AOP 切面自动记录每次调用的关键信息：
 * 模型名、输入/输出 token 估算、耗时、用户 ID、调用状态
 * @Author: ys
 * @Date: 2026/6/13
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ModelInvocationLog {

    /**
     * 模型名称
     *
     * 三种写法：
     * 1. 留空（默认）→ AOP 自动从返回值的 model 字段或方法参数中推断
     * 2. SpEL 表达式 → "#this.modelName" 或 "#request.model"
     * 3. 固定字符串 → "deepseek-chat"、"mimo-v2.5-pro"
     */
    String modelName() default "";

    /**
     * 调用场景
     *
     * 区分同一模型在不同业务下的调用，方便统计分析。
     * 常用值：chat（普通对话）、structured（结构化输出）、
     *         rag（知识库问答）、agent（Agent 调用）、eval（评测）
     */
    String scene() default "chat";

    /**
     * 调用描述
     *
     * 可选的中文说明，写入日志表方便排查。
     * 例如："需求分析助手"、"订单查询 Agent"
     */
    String description() default "";

    /**
     * 是否记录当前用户 ID
     *
     * true  → 从 Shiro SecurityUtils 获取登录用户
     * false → 匿名/系统调用，不关联用户
     */
    boolean recordUserId() default true;

    /**
     * 是否记录 Prompt 模板信息
     *
     * true  → AOP 尝试从方法参数中提取 promptCode / promptVersion，
     *         记录到日志，追踪"用了哪个版本 Prompt 效果如何"
     * false → 不记录
     */
    boolean recordPromptInfo() default true;

    /**
     * 是否异步写入日志
     *
     * true  → 用线程池异步写数据库，不阻塞接口响应（推荐生产用）
     * false → 同步写入，方便调试
     */
    boolean async() default true;
}
