package org.jeecg.modules.airag.practice.aspect.annotation;

import java.lang.annotation.*;

/**
 * 自定义熔断器注解
 *
 * @Author: ys
 * @Date: 2026-07-10
 */
//update-begin---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CircuitBreaker {
    /**
     * 熔断器名称，默认为类名.方法名
     */
    String value() default "";

    /**
     * 连续失败几次触发熔断，默认 5 次
     */
    int failureThreshold() default 5;

    /**
     * 熔断开启后的冷却时间（毫秒），默认 10000 毫秒（10秒）
     */
    long timeout() default 10000;

    /**
     * 降级方法名称，可选
     */
    String fallbackMethod() default "";
}
//update-end---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
