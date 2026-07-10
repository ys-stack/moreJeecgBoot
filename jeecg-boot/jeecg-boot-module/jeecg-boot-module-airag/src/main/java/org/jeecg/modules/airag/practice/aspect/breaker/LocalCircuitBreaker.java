package org.jeecg.modules.airag.practice.aspect.breaker;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端熔断器状态机 (无锁高并发安全实现)
 *
 * @Author: ys
 * @Date: 2026-07-10
 */
//update-begin---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
@Slf4j
public class LocalCircuitBreaker {
    
    /** 熔断器的三种状态 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold; // 连续失败阈值
    private final long halfOpenTimeoutMs; // 熔断冷却时间 (ms)

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private volatile long lastStateChangedTime = System.currentTimeMillis();

    public LocalCircuitBreaker(String name, int failureThreshold, long halfOpenTimeoutMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.halfOpenTimeoutMs = halfOpenTimeoutMs;
    }

    /**
     * 判断是否放行当前请求
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.OPEN) {
            // 冷却时间已过，转为半开状态，尝试放行
            if (System.currentTimeMillis() - lastStateChangedTime > halfOpenTimeoutMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[熔断器-{}] 状态切换: OPEN -> HALF_OPEN (冷却期已过，尝试部分放行请求)", name);
                    lastStateChangedTime = System.currentTimeMillis();
                    successCount.set(0);
                    failureCount.set(0);
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * 调用成功时的处理
     */
    public void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            int success = successCount.incrementAndGet();
            if (success >= 3) { // 半开状态下连续成功3次，恢复为 CLOSED
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    log.info("[熔断器-{}] 状态切换: HALF_OPEN -> CLOSED (服务已恢复健康)", name);
                    lastStateChangedTime = System.currentTimeMillis();
                    failureCount.set(0);
                }
            }
        } else if (current == State.CLOSED) {
            failureCount.set(0); // 正常闭合状态下，成功时清空连续失败计数
        }
    }

    /**
     * 调用异常时的处理
     */
    public void onFailure() {
        State current = state.get();
        if (current == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) { // 连续失败超过阈值，开启熔断
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    log.error("[熔断器-{}] 状态切换: CLOSED -> OPEN (连续失败达 {} 次，触发熔断保护，快速拒绝请求)", name, failureThreshold);
                    lastStateChangedTime = System.currentTimeMillis();
                }
            }
        } else if (current == State.HALF_OPEN) {
            // 半开状态下只要有一次失败，立刻重新进入熔断 (OPEN)
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                log.error("[熔断器-{}] 状态切换: HALF_OPEN -> OPEN (半开状态下请求发生异常，重新进入熔断)", name);
                lastStateChangedTime = System.currentTimeMillis();
            }
        }
    }

    public State getState() {
        return state.get();
    }
}
//update-end---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
