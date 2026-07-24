package org.jeecg.modules.airag.practice.vector.service;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.cache.util.FloatVectorCodec;
import org.jeecg.modules.airag.practice.cache.util.PracticeCacheKeyHasher;
import org.jeecg.modules.airag.practice.cache.metrics.PracticeCacheMetrics;
import org.jeecg.modules.airag.practice.vector.cache.EmbeddingCacheContext;
import org.jeecg.modules.airag.practice.vector.cache.service.IEmbeddingVectorCacheService;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class EmbeddingService {

    private static final long LOCK_WAIT_MILLIS = 2000L;

    @Resource
    private PracticeVectorConfig config;

    @Resource
    private PracticeCacheKeyHasher cacheKeyHasher;

    @Resource
    private IEmbeddingVectorCacheService embeddingCacheService;

    @Resource
    private EmbeddingApiClient embeddingApiClient;

    @Resource
    private PracticeCacheMetrics cacheMetrics;

    public float[] embed(
            String text,
            EmbeddingCacheContext context) {

        List<float[]> vectors = embedBatch(
                Collections.singletonList(text),
                context
        );

        return vectors.get(0);
    }

    public List<float[]> embedBatch(List<String> texts,EmbeddingCacheContext context) {

        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        if (context == null) {
            throw new IllegalArgumentException("EmbeddingCacheContext 不能为空");
        }

        String modelName = config.getEmbed().getModelName();
        String modelVersion = config.getEmbed().getModelVersion();
        String normalizationVersion = config.getEmbed().getNormalizationVersion();
        int dimensions = config.getEmbed().getDimensions();

        List<String> canonicalTexts = new ArrayList<>(texts.size());
        List<String> cacheKeys = new ArrayList<>(texts.size());
        List<float[]> results = new ArrayList<>(texts.size());

        for (String text : texts) {
            String canonicalText = cacheKeyHasher.normalizeEmbeddingText(text);

            if (canonicalText.isBlank()) {
                throw new IllegalArgumentException("Embedding 输入文本不能为空");
            }

            canonicalTexts.add(canonicalText);

            cacheKeys.add(embeddingCacheService.buildCacheKey(
                            context,
                            canonicalText,
                            modelName,
                            modelVersion,
                            normalizationVersion,
                            dimensions
                    )
            );
            results.add(null);
        }

        List<Integer> missIndexes = new ArrayList<>();

        for (int i = 0; i < canonicalTexts.size(); i++) {
            float[] cached = embeddingCacheService.get(
                    context,
                    canonicalTexts.get(i),
                    modelName,
                    modelVersion,
                    normalizationVersion,
                    dimensions
            );

            if (cached == null) {
                cacheMetrics.recordMiss("embedding");
                missIndexes.add(i);
            } else {
                results.set(i, cached);
            }
        }

        Map<Integer, String> ownedLocks = new LinkedHashMap<>();
        List<Integer> waitingIndexes = new ArrayList<>();

        for (Integer index : missIndexes) {
            String token = UUID.randomUUID().toString();
            String cacheKey = cacheKeys.get(index);

            if (embeddingCacheService.tryLock(cacheKey, token)) {
                ownedLocks.put(index, token);
            } else {
                waitingIndexes.add(index);
            }
        }

        try {
            if (!ownedLocks.isEmpty()) {
                List<Integer> ownerIndexes = new ArrayList<>(ownedLocks.keySet());
                List<String> apiTexts = ownerIndexes.stream().map(canonicalTexts::get).toList();
                List<float[]> apiVectors = embeddingApiClient.embedBatch(apiTexts);
                cacheMetrics.recordEmbeddingApiRequest(apiTexts.size());

                if (apiVectors.size() != ownerIndexes.size()) {
                    throw new IllegalStateException("Embedding API 返回数量与请求数量不一致");
                }

                for (int i = 0; i < ownerIndexes.size(); i++) {
                    int resultIndex = ownerIndexes.get(i);
                    float[] vector = apiVectors.get(i);

                    FloatVectorCodec.validate(vector, dimensions);

                    embeddingCacheService.put(
                            context,
                            canonicalTexts.get(resultIndex),
                            modelName,
                            modelVersion,
                            normalizationVersion,
                            dimensions,
                            vector
                    );

                    results.set(resultIndex, vector);
                }
            }
        } finally {
            ownedLocks.forEach((index, token) -> embeddingCacheService.unlock(cacheKeys.get(index), token));
        }

        List<Integer> unresolvedIndexes = new ArrayList<>();

        for (Integer index : waitingIndexes) {
            float[] vector = embeddingCacheService.waitForValue(
                    context,
                    canonicalTexts.get(index),
                    modelName,
                    modelVersion,
                    normalizationVersion,
                    dimensions,
                    LOCK_WAIT_MILLIS
            );

            if (vector == null) {
                unresolvedIndexes.add(index);
            } else {
                results.set(index, vector);
            }
        }

        if (!unresolvedIndexes.isEmpty()) {
            List<String> fallbackTexts = unresolvedIndexes.stream()
                    .map(canonicalTexts::get)
                    .toList();

            List<float[]> fallbackVectors = embeddingApiClient.embedBatch(fallbackTexts);
            cacheMetrics.recordEmbeddingApiRequest(fallbackTexts.size());

            if (fallbackVectors.size() != unresolvedIndexes.size()) {
                throw new IllegalStateException("Embedding API 降级返回数量与请求数量不一致");
            }

            for (int i = 0; i < unresolvedIndexes.size(); i++) {
                int resultIndex = unresolvedIndexes.get(i);
                float[] vector = fallbackVectors.get(i);

                FloatVectorCodec.validate(vector, dimensions);

                embeddingCacheService.put(
                        context,
                        canonicalTexts.get(resultIndex),
                        modelName,
                        modelVersion,
                        normalizationVersion,
                        dimensions,
                        vector
                );

                results.set(resultIndex, vector);
            }
        }

        for (int i = 0; i < results.size(); i++) {
            float[] vector = results.get(i);

            if (vector == null) {
                throw new IllegalStateException("Embedding 结果为空: index=" + i);
            }
            FloatVectorCodec.validate(vector, dimensions);
        }

        log.info("Embedding 完成: total={}, hit={}, api={}",
                texts.size(),
                texts.size() - missIndexes.size(),
                ownedLocks.size() + unresolvedIndexes.size()
        );

        return results;
    }
}
