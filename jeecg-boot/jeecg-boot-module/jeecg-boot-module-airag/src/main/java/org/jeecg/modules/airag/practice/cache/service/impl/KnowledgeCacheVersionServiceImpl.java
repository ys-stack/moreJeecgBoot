package org.jeecg.modules.airag.practice.cache.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.cache.mq.CacheInvalidationProducer;
import org.jeecg.modules.airag.practice.cache.service.IKnowledgeCacheVersionService;
import org.jeecg.modules.airag.practice.cache.util.PracticeCacheKeyHasher;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.mapper.AiKnowledgeBaseMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 知识库版本号缓存和延迟双删协调服务。 */
@Slf4j
@Service
public class KnowledgeCacheVersionServiceImpl implements IKnowledgeCacheVersionService {

    private static final String REDIS_PREFIX = "airag:knowledge-version:v1:";

    @Resource
    private AiKnowledgeBaseMapper knowledgeBaseMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheInvalidationProducer invalidationProducer;

    @Resource
    private PracticeCacheKeyHasher cacheKeyHasher;

    private final Cache<String, Long> localVersionCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    @Override
    public long getVersion(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return 0L;
        }

        Long localValue = localVersionCache.getIfPresent(knowledgeBaseId);
        if (localValue != null) {
            return localValue;
        }

        String redisKey = REDIS_PREFIX + knowledgeBaseId;
        try {
            String redisValue = stringRedisTemplate.opsForValue().get(redisKey);
            if (redisValue != null && !redisValue.isBlank()) {
                long version = Long.parseLong(redisValue);
                localVersionCache.put(knowledgeBaseId, version);
                return version;
            }
        } catch (Exception e) {
            log.warn("读取知识库版本 Redis 缓存失败: kbId={}, error={}",
                    knowledgeBaseId, e.getMessage());
        }

        AiKnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        long version = knowledgeBase == null || knowledgeBase.getCacheVersion() == null
                ? 0L
                : knowledgeBase.getCacheVersion();
        localVersionCache.put(knowledgeBaseId, version);

        try {
            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    String.valueOf(version),
                    1L,
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.warn("写入知识库版本 Redis 缓存失败: kbId={}, error={}",
                    knowledgeBaseId, e.getMessage());
        }
        return version;
    }

    @Override
    public String buildFingerprint(List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return cacheKeyHasher.hmac("knowledge-version", "empty");
        }
        List<String> versions = knowledgeBaseIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .map(id -> id + ":" + getVersion(id))
                .toList();
        return cacheKeyHasher.hmac("knowledge-version", String.join("|", versions));
    }

    @Override
    public void bumpVersion(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return;
        }

        // 第一次删除，防止更新期间继续使用旧版本。
        evictLocalAndRedis(knowledgeBaseId);
        invalidationProducer.sendKnowledgeChanged(knowledgeBaseId, 0L);

        int updated = knowledgeBaseMapper.incrementCacheVersion(knowledgeBaseId);
        if (updated == 0) {
            publishDeleted(knowledgeBaseId);
            return;
        }

        runAfterCommit(() -> {
            invalidationProducer.sendKnowledgeChanged(knowledgeBaseId, 0L);
            invalidationProducer.sendKnowledgeChanged(knowledgeBaseId, 1000L);
        });
    }

    @Override
    public void publishDeleted(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return;
        }
        evictLocalAndRedis(knowledgeBaseId);
        runAfterCommit(() -> {
            invalidationProducer.sendKnowledgeChanged(knowledgeBaseId, 0L);
            invalidationProducer.sendKnowledgeChanged(knowledgeBaseId, 1000L);
        });
    }

    @Override
    public void evictLocalAndRedis(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return;
        }
        localVersionCache.invalidate(knowledgeBaseId);
        try {
            stringRedisTemplate.delete(REDIS_PREFIX + knowledgeBaseId);
        } catch (Exception e) {
            log.warn("删除知识库版本 Redis 缓存失败: kbId={}, error={}",
                    knowledgeBaseId, e.getMessage());
        }
    }

    private void runAfterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            runnable.run();
                        }
                    }
            );
        } else {
            runnable.run();
        }
    }
}
