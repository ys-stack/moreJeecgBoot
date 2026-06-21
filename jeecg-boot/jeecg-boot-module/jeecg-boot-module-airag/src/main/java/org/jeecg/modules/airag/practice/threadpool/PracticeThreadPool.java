package org.jeecg.modules.airag.practice.threadpool;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自定义线程池 — 用于 practice 模块所有异步任务
 *
 * 特性：
 * 1. 线程命名：格式 "practice-{poolName}-N"，便于日志排查和 jstack 分析
 * 2. 异常记录：重写 afterExecute()，捕获未处理的 RuntimeException 并记录日志
 * 3. 运行时监控：累计任务数、失败数、拒绝数、当前活跃线程数等指标
 * 4. 安全关闭：支持优雅停机，等待已提交任务执行完毕
 *
 * 线程池参数设计（基于 8核/6.5GB 虚拟机）：
 * - corePoolSize = 8      （IO 密集型场景，核心线程 = CPU 核心数）
 * - maxPoolSize  = 16     （允许突发翻倍，SSE 流式任务等待 IO 时不阻塞其他任务）
 * - queueCapacity = 200   （有界队列，防止 OOM；超过时由 CallerRunsPolicy 降级）
 * - keepAlive = 60s       （非核心线程空闲 60s 回收）
 * - rejectionPolicy = CallerRunsPolicy（队列满时调用者线程执行，不丢弃任务）
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-21
 */
@Slf4j
public class PracticeThreadPool extends ThreadPoolExecutor {

    private final String poolName;

    // ==================== 监控计数器 ====================
    /** 累计提交任务数 */
    private final AtomicLong totalSubmitted = new AtomicLong(0);
    /** 累计成功完成任务数 */
    private final AtomicLong totalCompleted = new AtomicLong(0);
    /** 累计异常任务数 */
    private final AtomicLong totalFailed = new AtomicLong(0);
    /** 累计被拒绝任务数 */
    private final AtomicLong totalRejected = new AtomicLong(0);

    public PracticeThreadPool(int corePoolSize, int maximumPoolSize, long keepAliveTime,
                              int queueCapacity, String poolName) {
        super(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new PracticeThreadFactory(poolName),
                new LoggingRejectedHandler(poolName)
        );
        this.poolName = poolName;
        log.info("[PracticeThreadPool] 初始化完成: name={}, core={}, max={}, queue={}",
                poolName, corePoolSize, maximumPoolSize, queueCapacity);
    }

    // ==================== 生命周期钩子 ====================

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        totalSubmitted.incrementAndGet();
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        totalCompleted.incrementAndGet();

        // 捕获未处理异常（execute 提交的任务异常会传到这里）
        if (t != null) {
            totalFailed.incrementAndGet();
            log.error("[{}] 任务执行异常: {}", poolName, t.getMessage(), t);
        }

        // submit 提交的任务，异常封装在 Future 里，需要额外检查
        if (t == null && r instanceof Future<?>) {
            try {
                ((Future<?>) r).get();
            } catch (ExecutionException e) {
                totalFailed.incrementAndGet();
                log.error("[{}] 任务内部异常: {}", poolName, e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 监控指标 ====================

    /**
     * 获取线程池运行时指标快照（供监控接口调用）
     */
    public PoolMetrics getMetrics() {
        return PoolMetrics.builder()
                .poolName(poolName)
                .corePoolSize(getCorePoolSize())
                .maxPoolSize(getMaximumPoolSize())
                .activeCount(getActiveCount())
                .poolSize(getPoolSize())
                .largestPoolSize(getLargestPoolSize())
                .queueSize(getQueue().size())
                .queueCapacity(getQueueCapacity())
                .totalSubmitted(totalSubmitted.get())
                .totalCompleted(totalCompleted.get())
                .totalFailed(totalFailed.get())
                .totalRejected(totalRejected.get())
                .isShutdown(isShutdown())
                .isTerminated(isTerminated())
                .build();
    }

    /**
     * 获取拒绝计数器引用（供自定义 RejectedHandler 使用）
     */
    public AtomicLong getRejectedCounter() {
        return totalRejected;
    }

    private int getQueueCapacity() {
        return getQueue() instanceof ArrayBlockingQueue<?> ?
                ((ArrayBlockingQueue<?>) getQueue()).remainingCapacity() + getQueue().size() : -1;
    }

    // ==================== 线程工厂：命名线程 ====================

    /**
     * 自定义线程工厂 — 为每个线程赋予 "practice-{poolName}-N" 格式的名称
     */
    private static class PracticeThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        PracticeThreadFactory(String poolName) {
            this.namePrefix = "practice-" + poolName + "-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            // 非守护线程，确保任务执行完毕
            t.setDaemon(false);
            // 默认优先级
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    // ==================== 拒绝策略：记录日志 ====================

    /**
     * 拒绝处理器 — 队列满时由调用者线程执行（CallerRunsPolicy），同时记录日志和计数
     */
    private static class LoggingRejectedHandler implements RejectedExecutionHandler {
        private final String poolName;

        LoggingRejectedHandler(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("[{}] 线程池已满，任务降级到调用者线程执行! active={}, queue={}, poolSize={}",
                    poolName, executor.getActiveCount(),
                    executor.getQueue().size(), executor.getPoolSize());

            // 计数拒绝
            if (executor instanceof PracticeThreadPool) {
                ((PracticeThreadPool) executor).getRejectedCounter().incrementAndGet();
            }

            // 降级：由调用者线程执行（不丢弃任务）
            if (!executor.isShutdown()) {
                r.run();
            }
        }
    }
}
