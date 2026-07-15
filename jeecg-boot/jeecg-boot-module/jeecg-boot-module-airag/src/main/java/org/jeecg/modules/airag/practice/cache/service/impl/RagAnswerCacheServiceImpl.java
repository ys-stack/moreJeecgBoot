package org.jeecg.modules.airag.practice.cache.service.impl;

import com.alibaba.fastjson.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.cache.context.RagAnswerCacheContext;
import org.jeecg.modules.airag.practice.cache.entity.FaqCacheItem;
import org.jeecg.modules.airag.practice.cache.metrics.PracticeCacheMetrics;
import org.jeecg.modules.airag.practice.cache.service.IRagAnswerCacheService;
import org.jeecg.modules.airag.practice.cache.util.PracticeCacheKeyHasher;
import org.jeecg.modules.airag.practice.cache.util.PracticeCachePayloadCipher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * RAG 答案两级缓存：本地 Caffeine + Redis。
 *
 * <p>缓存中仅保存完整回答结果，不负责权限校验。调用方必须先完成会话和知识库权限校验。</p>
 */
@Slf4j
@Service
public class RagAnswerCacheServiceImpl implements IRagAnswerCacheService {

    private static final String REDIS_PREFIX = "airag:rag-answer:v1:";
    private static final long REDIS_BASE_TTL_SECONDS = 10L * 60;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PracticeCacheKeyHasher cacheKeyHasher;

    @Resource
    private PracticeCacheMetrics cacheMetrics;

    @Resource
    private PracticeCachePayloadCipher payloadCipher;

    private final Cache<String, FaqCacheItem> localCache = Caffeine.newBuilder()
            .maximumWeight(32L * 1024 * 1024)
            .weigher((Weigher<String, FaqCacheItem>) (key, value) -> {
                int answerBytes = value.getAnswer() == null ? 0 : value.getAnswer().length() * 2;
                int contextBytes = value.getRagContextJson() == null
                        ? 0
                        : value.getRagContextJson().length() * 2;
                return Math.max(1, answerBytes + contextBytes + 512);
            })
            .expireAfterWrite(Duration.ofMinutes(2))
            .recordStats()
            .build();

    @Override
    public FaqCacheItem get(RagAnswerCacheContext context, String question) {
        String cacheKey = buildCacheKey(context, question);
        FaqCacheItem localValue = localCache.getIfPresent(cacheKey);
        if (isValid(localValue)) {
            cacheMetrics.recordHit("rag-answer", "caffeine");
            log.debug("RAG 答案 Caffeine 命中: key={}", shortKey(cacheKey));
            return localValue;
        }
        if (localValue != null) {
            localCache.invalidate(cacheKey);
        }

        try {
            String json = stringRedisTemplate.opsForValue().get(REDIS_PREFIX + cacheKey);
            if (json == null || json.isBlank()) {
                cacheMetrics.recordMiss("rag-answer");
                return null;
            }

            // Redis 只保存 AES-GCM 密文，密文被篡改时认证标签校验会直接失败。
            String plaintext = payloadCipher.decrypt(json);
            FaqCacheItem redisValue = JSON.parseObject(plaintext, FaqCacheItem.class);
            if (!isValid(redisValue)) {
                stringRedisTemplate.delete(REDIS_PREFIX + cacheKey);
                return null;
            }

            localCache.put(cacheKey, redisValue);
            cacheMetrics.recordHit("rag-answer", "redis");
            log.debug("RAG 答案 Redis 命中: key={}", shortKey(cacheKey));
            return redisValue;
        } catch (Exception e) {
            log.warn("读取 RAG 答案 Redis 缓存失败: key={}, error={}",
                    shortKey(cacheKey), e.getMessage());
            try {
                // 解密或反序列化失败的记录不可继续复用，主动清理以便后续重建。
                stringRedisTemplate.delete(REDIS_PREFIX + cacheKey);
            } catch (Exception ignored) {
                log.debug("清理异常的 RAG Redis 缓存失败: key={}", shortKey(cacheKey));
            }
            return null;
        }
    }

    @Override
    public void put(RagAnswerCacheContext context, String question, FaqCacheItem item) {
        if (item == null || item.getAnswer() == null || item.getAnswer().isBlank()) {
            return;
        }

        String normalizedQuestion = cacheKeyHasher.normalizeQuestion(question);
        String cacheKey = buildCacheKey(context, question);
        long now = System.currentTimeMillis();
        long ttlSeconds = REDIS_BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(120L);

        item.setCacheKey(cacheKey);
        item.setTenantId(context.tenantId());
        item.setQuestionHash(cacheKeyHasher.hmac("question", normalizedQuestion));
        item.setKnowledgeBaseIds(context.knowledgeBaseIds());
        item.setKnowledgeVersionFingerprint(context.knowledgeVersionFingerprint());
        item.setCreateTime(now);
        item.setExpireTime(now + TimeUnit.SECONDS.toMillis(ttlSeconds));

        localCache.put(cacheKey, item);

        try {
            stringRedisTemplate.opsForValue().set(
                    REDIS_PREFIX + cacheKey,
                    payloadCipher.encrypt(JSON.toJSONString(item)),
                    ttlSeconds,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("写入 RAG 答案 Redis 缓存失败: key={}, error={}",
                    shortKey(cacheKey), e.getMessage());
        }
    }

    @Override
    public void evictLocalByKnowledgeBaseId(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return;
        }
        localCache.asMap().entrySet().removeIf(entry -> {
            List<String> knowledgeBaseIds = entry.getValue().getKnowledgeBaseIds();
            return knowledgeBaseIds != null && knowledgeBaseIds.contains(knowledgeBaseId);
        });
    }

    private String buildCacheKey(RagAnswerCacheContext context, String question) {
        return cacheKeyHasher.hmac(
                "rag-answer",
                "v1",
                context.tenantId(),
                String.join(",", context.knowledgeBaseIds()),
                context.knowledgeVersionFingerprint(),
                context.modelName(),
                context.promptVersion(),
                cacheKeyHasher.normalizeQuestion(question)
        );
    }

    private boolean isValid(FaqCacheItem item) {
        return item != null
                && item.getExpireTime() != null
                && item.getExpireTime() > System.currentTimeMillis()
                && item.getAnswer() != null
                && !item.getAnswer().isBlank();
    }

    private String shortKey(String cacheKey) {
        return cacheKey.length() <= 12 ? cacheKey : cacheKey.substring(0, 12);
    }
}
