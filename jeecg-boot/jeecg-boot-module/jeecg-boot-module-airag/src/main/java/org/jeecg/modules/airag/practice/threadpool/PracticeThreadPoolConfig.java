package org.jeecg.modules.airag.practice.threadpool;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 自定义线程池 Spring 配置
 *
 * 注册两个线程池 Bean：
 * - practiceStreamPool: SSE 流式任务专用（IO 密集型，线程较多）
 * - practiceAsyncPool: 通用异步任务（日志写入等，线程较少）
 *
 * 基于 8核/6.5GB 虚拟机配置参数。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-21
 */
@Slf4j
@Configuration
public class PracticeThreadPoolConfig {

    private PracticeThreadPool streamPool;
    private PracticeThreadPool asyncPool;

    /**
     * SSE 流式任务线程池
     *
     * 场景：chatStream / ragChatStream 异步调用大模型并推送 SSE 事件
     * 特点：IO 密集型（等待 HTTP 响应），需要较多线程
     */
    @Bean("practiceStreamPool")
    public PracticeThreadPool practiceStreamPool() {
        streamPool = new PracticeThreadPool(
                8,    // corePoolSize = CPU 核心数
                16,   // maxPoolSize = 2x CPU（IO 密集型允许更多并发）
                60,   // keepAlive = 60s
                200,  // queueCapacity = 200（有界队列防 OOM）
                "stream"
        );
        return streamPool;
    }

    /**
     * 通用异步任务线程池
     *
     * 场景：异步写日志、异步更新统计数据等
     * 特点：轻量任务，线程数不需要太多
     */
    @Bean("practiceAsyncPool")
    public PracticeThreadPool practiceAsyncPool() {
        asyncPool = new PracticeThreadPool(
                4,    // corePoolSize = 4（轻量任务）
                8,    // maxPoolSize = 8
                60,   // keepAlive = 60s
                100,  // queueCapacity = 100
                "async"
        );
        return asyncPool;
    }

    /**
     * 优雅停机：Spring 容器关闭时等待任务执行完毕
     */
    @PreDestroy
    public void shutdown() {
        shutdownPool("stream", streamPool);
        shutdownPool("async", asyncPool);
    }

    private void shutdownPool(String name, PracticeThreadPool pool) {
        if (pool == null) return;
        log.info("[PracticeThreadPool] 开始关闭 {} 线程池...", name);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[PracticeThreadPool] {} 线程池 30s 内未终止，强制关闭", name);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[PracticeThreadPool] {} 线程池已关闭 | 指标: {}", name, pool.getMetrics());
    }
}
