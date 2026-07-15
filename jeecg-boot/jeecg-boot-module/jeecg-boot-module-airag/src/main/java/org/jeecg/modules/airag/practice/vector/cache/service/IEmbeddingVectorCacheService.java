package org.jeecg.modules.airag.practice.vector.cache.service;

import org.jeecg.modules.airag.practice.vector.cache.EmbeddingCacheContext;

public interface IEmbeddingVectorCacheService {

    float[] get(
            EmbeddingCacheContext context,
            String canonicalText,
            String modelName,
            String modelVersion,
            String normalizationVersion,
            int dimensions
    );

    void put(
            EmbeddingCacheContext context,
            String canonicalText,
            String modelName,
            String modelVersion,
            String normalizationVersion,
            int dimensions,
            float[] vector
    );

    boolean tryLock(String cacheKey, String lockToken);

    void unlock(String cacheKey, String lockToken);

    float[] waitForValue(
            EmbeddingCacheContext context,
            String canonicalText,
            String modelName,
            String modelVersion,
            String normalizationVersion,
            int dimensions,
            long waitMillis
    );

    String buildCacheKey(
            EmbeddingCacheContext context,
            String canonicalText,
            String modelName,
            String modelVersion,
            String normalizationVersion,
            int dimensions
    );
}