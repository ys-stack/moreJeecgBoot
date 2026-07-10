package org.jeecg.modules.airag.practice.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.modules.airag.practice.aspect.annotation.CircuitBreaker;
import org.jeecg.modules.airag.practice.aspect.breaker.LocalCircuitBreaker;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端熔断切面 —— 统计失败、快速失败、触发 Fallback 降级
 *
 * @Author: ys
 * @Date: 2026-07-10
 */
//update-begin---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
@Slf4j
@Aspect
@Component
public class CircuitBreakerAspect {

    /** 熔断器注册中心 */
    private final Map<String, LocalCircuitBreaker> registry = new ConcurrentHashMap<>();

    @Around("@annotation(org.jeecg.modules.airag.practice.aspect.annotation.CircuitBreaker)")
    public Object around(ProceedingJoinPoint pjp, CircuitBreaker cbAnnotation) throws Throwable {
        // 解析熔断器名称，若为空则默认使用 类名.方法名
        String breakerName = cbAnnotation.value().isEmpty() 
                ? resolveBreakerName(pjp) 
                : cbAnnotation.value();

        // 注册或获取熔断器实例
        LocalCircuitBreaker breaker = registry.computeIfAbsent(breakerName, name -> 
                new LocalCircuitBreaker(name, cbAnnotation.failureThreshold(), cbAnnotation.timeout())
        );

        // 判断是否允许放行当前请求（快速失败）
        if (!breaker.allowRequest()) {
            log.warn("[熔断保护] 熔断器 [{}] 处于开启状态，直接拒绝服务请求", breakerName);
            if (!cbAnnotation.fallbackMethod().isEmpty()) {
                return executeFallback(pjp, cbAnnotation.fallbackMethod());
            }
            throw new JeecgBootException("系统服务繁忙，ES检索暂时不可用 (触发熔断保护)");
        }

        try {
            Object result = pjp.proceed();
            // 调用成功，通知状态机
            breaker.onSuccess();
            return result;
        } catch (Throwable t) {
            // 调用发生异常，通知状态机
            breaker.onFailure();
            log.error("[熔断监测] 熔断器 [{}] 监测到业务调用异常: {}", breakerName, t.getMessage());
            
            // 如果配置了 Fallback，则进行降级执行
            if (!cbAnnotation.fallbackMethod().isEmpty()) {
                return executeFallback(pjp, cbAnnotation.fallbackMethod());
            }
            throw t;
        }
    }

    /**
     * 解析方法默认的熔断器名
     */
    private String resolveBreakerName(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return sig.getDeclaringType().getSimpleName() + "." + sig.getName();
    }

    /**
     * 利用反射执行同一个类下的降级方法
     */
    private Object executeFallback(ProceedingJoinPoint pjp, String fallbackMethodName) throws Throwable {
        log.info("[熔断降级] 触发服务降级逻辑，调用降级方法: {}", fallbackMethodName);
        Object target = pjp.getTarget();
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        try {
            Method fallbackMethod = target.getClass().getMethod(fallbackMethodName, method.getParameterTypes());
            fallbackMethod.setAccessible(true);
            return fallbackMethod.invoke(target, pjp.getArgs());
        } catch (NoSuchMethodException e) {
            log.error("[熔断降级] 找不到对应的降级方法: {}, 请确保方法存在且参数列表一致", fallbackMethodName);
            throw new JeecgBootException("系统服务繁忙，且降级配置发生异常");
        } catch (Exception e) {
            log.error("[熔断降级] 执行降级方法时发生内部异常", e);
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
//update-end---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
