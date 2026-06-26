package org.jeecg.modules.airag.practice.vector.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.springframework.http.*;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.*;

/**
 * Embedding 服务（带缓存 + 重试降级）
 *
 * 调用硅基流动 bge-m3 模型（OpenAI 兼容接口）将文本转为向量。
 * - Caffeine 本地缓存：相同文本 24h 内不重复调用 API
 * - Spring Retry：API 失败自动重试 3 次（指数退避 1s→2s→4s），全部失败降级返回零向量
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@Service
public class EmbeddingService {

    @Resource
    private PracticeVectorConfig config;

    @Resource
    private RestTemplate practiceEmbedRestTemplate;

    /**
     * 本地缓存：text MD5 → float[]
     * 最多 5000 条，24h 过期，约占用 20MB 内存
     */
    private final Cache<String, float[]> embedCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    // ==================== 公开方法 ====================

    /**
     * 单条文本 Embedding（带缓存）
     *
     * @param text 待向量化文本
     * @return 1024维浮点向量
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding 输入文本不能为空");
        }

        String cacheKey = DigestUtils.md5Hex(text);
        float[] cached = embedCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("Embedding 缓存命中: key={}", cacheKey.substring(0, 8));
            return cached;
        }

        List<float[]> results = embedBatch(List.of(text));
        float[] vector = results.get(0);

        if (isZeroVector(vector)) {
            log.warn("Embedding 降级：返回零向量，检索结果将为空");
        }

        return vector;
    }

    /**
     * 批量文本 Embedding（带缓存 + 重试降级）
     *
     * 缓存命中的直接返回，未命中的调 API，API 全部失败则降级返回零向量。
     *
     * @param texts 文本列表
     * @return 对应的向量列表（顺序与输入一致）
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 分离缓存命中的和未命中的
        List<float[]> results = new ArrayList<>(texts.size());
        List<Integer> missIndexes = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            String cacheKey = DigestUtils.md5Hex(text);
            float[] cached = embedCache.getIfPresent(cacheKey);
            if (cached != null) {
                results.add(cached);
            } else {
                results.add(null);
                missIndexes.add(i);
                missTexts.add(text);
            }
        }

        // 只对未命中的调 API（带重试 + 降级）
        if (!missTexts.isEmpty()) {
            List<float[]> apiResults = callEmbeddingApi(missTexts);
            for (int j = 0; j < missIndexes.size(); j++) {
                int idx = missIndexes.get(j);
                float[] vec = apiResults.get(j);
                results.set(idx, vec);
                // 写入缓存（降级的零向量也缓存，避免反复重试）
                embedCache.put(DigestUtils.md5Hex(missTexts.get(j)), vec);
            }
        }

        log.info("Embedding 完成: 总数={}, 缓存命中={}, API调用={}",
                texts.size(), texts.size() - missTexts.size(), missTexts.size());
        return results;
    }

    // ==================== API 调用（带重试） ====================

    /**
     * 实际调用 Embedding API，失败自动重试 3 次（1s → 2s → 4s）
     *
     * @Retryable 通过 AOP 代理生效，embedBatch() 调用此方法时走的是代理对象，重试正常触发。
     */
    @Retryable(
            retryFor = {RuntimeException.class},
            maxAttempts = 3,
            backoff = @org.springframework.retry.annotation.Backoff(delay = 1000, multiplier = 2)
    )
    public List<float[]> callEmbeddingApi(List<String> texts) {
        // 构建请求体（OpenAI 兼容格式）
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getEmbed().getModelName());
        requestBody.put("input", texts);

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getEmbed().getApiKey());

        String url = config.getEmbed().getBaseUrl() + "/embeddings";
        log.info("调用 Embedding API: url={}, model={}, 文本数={}", url, config.getEmbed().getModelName(), texts.size());

        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
        ResponseEntity<String> response = practiceEmbedRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new RuntimeException("Embedding API 返回异常: status=" + response.getStatusCode());
        }

        // 解析响应
        JSONObject respJson = JSON.parseObject(response.getBody());
        JSONArray dataArray = respJson.getJSONArray("data");

        if (dataArray == null || dataArray.size() != texts.size()) {
            throw new RuntimeException("Embedding 返回数量不匹配: 期望=" + texts.size()
                    + ", 实际=" + (dataArray == null ? 0 : dataArray.size()));
        }

        // 按 index 排序（API 返回可能乱序）并提取向量
        List<JSONObject> sortedData = new ArrayList<>();
        for (int i = 0; i < dataArray.size(); i++) {
            sortedData.add(dataArray.getJSONObject(i));
        }
        sortedData.sort(Comparator.comparingInt(o -> o.getIntValue("index")));

        List<float[]> vectors = new ArrayList<>(dataArray.size());
        for (JSONObject item : sortedData) {
            JSONArray embedding = item.getJSONArray("embedding");
            float[] vec = new float[embedding.size()];
            for (int j = 0; j < embedding.size(); j++) {
                vec[j] = embedding.getFloatValue(j);
            }
            vectors.add(vec);
        }

        // 记录 token 用量
        JSONObject usage = respJson.getJSONObject("usage");
        if (usage != null) {
            log.info("Embedding API 成功: 文本数={}, tokens={}", texts.size(), usage.getIntValue("total_tokens"));
        }

        return vectors;
    }

    /**
     * 重试耗尽后的降级方法：返回零向量
     * 参数和返回值必须与 callEmbeddingApi 一致
     */
    @Recover
    public List<float[]> callEmbeddingApiRecover(RuntimeException e, List<String> texts) {
        log.error("Embedding API 重试耗尽，降级返回零向量，文本数={}", texts.size(), e);
        int dims = config.getEmbed().getDimensions();
        List<float[]> fallback = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            fallback.add(new float[dims]);
        }
        return fallback;
    }

    // ==================== 内部方法 ====================

    /**
     * 判断是否为全零向量（降级标记）
     */
    private boolean isZeroVector(float[] vec) {
        for (float v : vec) {
            if (v != 0.0f) return false;
        }
        return true;
    }
}
