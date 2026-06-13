package org.jeecg.modules.airag.practice.aspect.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流注解 —— 基于 Redis 计数器
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 限流次数 */
    int count() default 10;

    /** 时间窗口长度 */
    long time() default 1;

    /** 时间单位，默认分钟 */
    TimeUnit timeUnit() default TimeUnit.MINUTES;

    /**
     * 限流维度
     * AUTO  — 已登录按用户，未登录按 IP（默认）
     * USER  — 仅按用户 ID，未登录则放行
     * IP    — 仅按客户端 IP
     * GLOBAL — 全局共享一个计数器（不区分用户）
     */
    Dimension dimension() default Dimension.AUTO;

    /** Redis key 前缀，为空时取 类名:方法名 */
    String key() default "";

    /** 超限提示语 */
    String message() default "请求过于频繁，请稍后再试";

    /** 限流维度枚举 */
    enum Dimension {
        AUTO, USER, IP, GLOBAL
    }
}
