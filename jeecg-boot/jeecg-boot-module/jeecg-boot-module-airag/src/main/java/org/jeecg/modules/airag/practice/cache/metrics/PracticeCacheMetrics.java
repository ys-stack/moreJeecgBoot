package org.jeecg.modules.airag.practice.cache.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 缓存与 Embedding API 指标。
 *
 * <p>只使用固定枚举型标签，禁止把租户、问题、缓存键等高基数字段放入标签。</p>
 */
@Component
public class PracticeCacheMetrics {

    @Resource
    private MeterRegistry meterRegistry;

    public void recordHit(String cacheName, String level) {
        meterRegistry.counter(
                "practice.cache.hit.total",
                "cache", cacheName,
                "level", level
        ).increment();
    }

    public void recordMiss(String cacheName) {
        meterRegistry.counter(
                "practice.cache.miss.total",
                "cache", cacheName
        ).increment();
    }

    public void recordEmbeddingApiRequest(int textCount) {
        meterRegistry.counter(
                "practice.embedding.api.request.total"
        ).increment();
        meterRegistry.counter(
                "practice.embedding.api.text.total"
        ).increment(textCount);
    }
}
