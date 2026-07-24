package org.jeecg.modules.airag.practice.vector.cache.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.cache.util.FloatVectorCodec;
import org.jeecg.modules.airag.practice.cache.util.PracticeCacheKeyHasher;
import org.jeecg.modules.airag.practice.cache.metrics.PracticeCacheMetrics;
import org.jeecg.modules.airag.practice.vector.cache.EmbeddingCacheContext;
import org.jeecg.modules.airag.practice.vector.cache.entity.AiEmbeddingCache;
import org.jeecg.modules.airag.practice.vector.cache.mapper.AiEmbeddingCacheMapper;
import org.jeecg.modules.airag.practice.vector.cache.service.IEmbeddingVectorCacheService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class EmbeddingVectorCacheServiceImpl implements IEmbeddingVectorCacheService {

    private static final String REDIS_KEY_PREFIX = "airag:embedding:v1:";
    private static final String LOCK_KEY_PREFIX = "airag:embedding:lock:v1:";

    private static final long REDIS_BASE_TTL_SECONDS = 7L * 24 * 3600;
    private static final long LOCK_TTL_SECONDS = 30L;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] "
                            + "then return redis.call('del', KEYS[1]) "
                            + "else return 0 end", Long.class
            );

    @Resource
    private AiEmbeddingCacheMapper embeddingCacheMapper;

    @Resource
    private PracticeCacheKeyHasher cacheKeyHasher;

    @Resource
    private PracticeCacheMetrics cacheMetrics;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Qualifier("embeddingVectorRedisTemplate")
    private RedisTemplate<String, byte[]> embeddingVectorRedisTemplate;

    //L0本地缓存
    private final Cache<String, float[]> localCache = Caffeine.newBuilder()
            .maximumWeight(64L * 1024 * 1024)
            .weigher((Weigher<String, float[]>) (key, vector) ->
                    Math.max(1, vector.length * Float.BYTES))
            .expireAfterWrite(Duration.ofHours(24))
            .recordStats()
            .build();

    @Override
    public float[] get(EmbeddingCacheContext context, String canonicalText, String modelName, String modelVersion, String normalizationVersion, int dimensions) {
        //缓存key
        String cacheKey = buildCacheKey(
                context,
                canonicalText,
                modelName,
                modelVersion,
                normalizationVersion,
                dimensions
        );
        //L0
        float[] localValue = localCache.getIfPresent(cacheKey);
        if (localValue != null) {
            cacheMetrics.recordHit("embedding", "caffeine");
            log.debug("Embedding L0 Caffeine 命中: key={}", shortKey(cacheKey));
            return FloatVectorCodec.copy(localValue);
        }
        //L1
        String redisKey = REDIS_KEY_PREFIX + cacheKey;
        try {
            byte[] redisValue = embeddingVectorRedisTemplate.opsForValue().get(redisKey);

            if (redisValue != null && redisValue.length > 0) {
                float[] vector = FloatVectorCodec.decode(redisValue);
                FloatVectorCodec.validate(vector, dimensions);
                localCache.put(cacheKey, FloatVectorCodec.copy(vector));
                cacheMetrics.recordHit("embedding", "redis");

                log.debug("Embedding L1 Redis 命中: key={}", shortKey(cacheKey));

                return FloatVectorCodec.copy(vector);
            }
        } catch (Exception e) {
            log.warn("读取 Embedding Redis 缓存失败: key={}, error={}",shortKey(cacheKey), e.getMessage());
        }

        AiEmbeddingCache dbCache;
        try {
            dbCache = embeddingCacheMapper.selectOne(new LambdaQueryWrapper<AiEmbeddingCache>()
                            .eq(AiEmbeddingCache::getCacheKey, cacheKey)
                            .last("LIMIT 1")
            );
        } catch (Exception e) {
            // 缓存是优化项，MySQL 缓存表异常时回退到 Embedding API，不能阻断主链路。
            log.warn("读取 Embedding MySQL 缓存失败: key={}, error={}",shortKey(cacheKey), e.getMessage());
            return null;
        }

        if (dbCache == null || dbCache.getVectorData() == null) {
            return null;
        }

        float[] vector;
        try {
            String actualChecksum = cacheKeyHasher.sha256(dbCache.getVectorData());
            if (!actualChecksum.equals(dbCache.getVectorChecksum())) {
                throw new IllegalStateException("向量校验和不匹配");
            }
            vector = FloatVectorCodec.decode(dbCache.getVectorData());
            FloatVectorCodec.validate(vector, dimensions);
        } catch (Exception e) {
            // 损坏记录必须清理，否则唯一键会阻止后续正确向量持久化。
            log.error("Embedding MySQL 缓存数据损坏: key={}, error={}", shortKey(cacheKey), e.getMessage());
            deleteCorruptRecord(dbCache);
            return null;
        }

        localCache.put(cacheKey, FloatVectorCodec.copy(vector));
        writeRedis(redisKey, dbCache.getVectorData());
        cacheMetrics.recordHit("embedding", "mysql");
        try {
            embeddingCacheMapper.touchLastHitTime(cacheKey);
        } catch (Exception e) {
            log.debug("更新 Embedding 最近命中时间失败: key={}, error={}", shortKey(cacheKey), e.getMessage());
        }
        log.debug("Embedding L2 MySQL 命中: key={}", shortKey(cacheKey));
        return FloatVectorCodec.copy(vector);
    }

    @Override
    public void put(EmbeddingCacheContext context, String canonicalText, String modelName, String modelVersion, String normalizationVersion, int dimensions, float[] vector) {
        //向量维度校验
        FloatVectorCodec.validate(vector, dimensions);

        String cacheKey = buildCacheKey(
                context,
                canonicalText,
                modelName,
                modelVersion,
                normalizationVersion,
                dimensions
        );

        byte[] vectorData = FloatVectorCodec.encode(vector);
        String checksum = cacheKeyHasher.sha256(vectorData);
        Date now = new Date();

        AiEmbeddingCache entity = new AiEmbeddingCache()
                .setCacheKey(cacheKey)
                .setTenantId(context.tenantId())
                .setModelName(modelName)
                .setModelVersion(modelVersion)
                .setNormalizationVersion(normalizationVersion)
                .setDimensions(dimensions)
                .setVectorData(vectorData)
                .setVectorChecksum(checksum)
                .setCreateTime(now)
                .setUpdateTime(now)
                .setLastHitTime(now);

        try {
            embeddingCacheMapper.insert(entity);
        } catch (DataIntegrityViolationException duplicateException) {
            log.debug("Embedding MySQL 缓存已被其他线程写入: key={}",
                    shortKey(cacheKey));
        } catch (Exception e) {
            // 持久层缓存写失败时仍返回刚生成的向量，并继续回填 Redis/Caffeine。
            log.warn("写入 Embedding MySQL 缓存失败: key={}, error={}",
                    shortKey(cacheKey), e.getMessage());
        }

        writeRedis(REDIS_KEY_PREFIX + cacheKey, vectorData);
        localCache.put(cacheKey, FloatVectorCodec.copy(vector));
    }

    @Override
    public boolean tryLock(String cacheKey, String lockToken) {
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                    LOCK_KEY_PREFIX + cacheKey,
                    lockToken,
                    LOCK_TTL_SECONDS,
                    TimeUnit.SECONDS
            );

            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.warn("获取 Embedding 分布式锁失败，允许当前请求降级重建: key={}, error={}",
                    shortKey(cacheKey), e.getMessage());
            return true;
        }
    }

    @Override
    public void unlock(String cacheKey, String lockToken) {
        try {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    Collections.singletonList(LOCK_KEY_PREFIX + cacheKey),
                    lockToken
            );
        } catch (Exception e) {
            log.warn("释放 Embedding 分布式锁失败: key={}, error={}",
                    shortKey(cacheKey), e.getMessage());
        }
    }

    @Override
    public float[] waitForValue(EmbeddingCacheContext context, String canonicalText, String modelName, String modelVersion, String normalizationVersion, int dimensions, long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;

        while (System.currentTimeMillis() < deadline) {
            float[] value = get(
                    context,
                    canonicalText,
                    modelName,
                    modelVersion,
                    normalizationVersion,
                    dimensions
            );

            if (value != null) {
                return value;
            }

            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        return null;
    }

    @Override
    public String buildCacheKey(
            EmbeddingCacheContext context,
            String canonicalText,
            String modelName,
            String modelVersion,
            String normalizationVersion,
            int dimensions) {

        return cacheKeyHasher.hmac(
                "embedding",
                "v1",
                context.tenantId(),
                modelName,
                modelVersion,
                normalizationVersion,
                String.valueOf(dimensions),
                canonicalText
        );
    }

    /** redis写入，带随机抖动 **/
    private void writeRedis(String redisKey, byte[] vectorData) {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(3600L);

            embeddingVectorRedisTemplate.opsForValue().set(
                    redisKey,
                    vectorData,
                    REDIS_BASE_TTL_SECONDS + jitter,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            log.warn("写入 Embedding Redis 缓存失败: key={}, error={}",
                    redisKey, e.getMessage());
        }
    }

    private void deleteCorruptRecord(AiEmbeddingCache dbCache) {
        try {
            embeddingCacheMapper.deleteById(dbCache.getId());
        } catch (Exception deleteException) {
            log.warn("清理损坏的 Embedding MySQL 缓存失败: key={}, error={}",
                    shortKey(dbCache.getCacheKey()), deleteException.getMessage());
        }
    }

    private String shortKey(String cacheKey) {
        if (cacheKey == null) {
            return "null";
        }
        return cacheKey.length() <= 12 ? cacheKey : cacheKey.substring(0, 12);
    }
}
