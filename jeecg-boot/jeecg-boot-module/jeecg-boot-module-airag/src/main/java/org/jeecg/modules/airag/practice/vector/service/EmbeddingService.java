package org.jeecg.modules.airag.practice.vector.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.*;

/**
 * Embedding 服务
 *
 * 调用硅基流动 bge-m3 模型（OpenAI 兼容接口）将文本转为向量。
 * 接口：POST {baseUrl}/embeddings
 * 请求体：{ "model": "BAAI/bge-m3", "input": ["text1", "text2"], "dimensions": 1024 }
 * 响应体：{ "data": [{ "embedding": [0.1, 0.2, ...] }, ...] }
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
    @Qualifier("practiceEmbedRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 单条文本 Embedding
     *
     * @param text 待向量化文本
     * @return 1024维浮点向量
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding 输入文本不能为空");
        }
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    /**
     * 批量文本 Embedding
     * 硅基流动支持数组输入，一次请求多条文本（建议不超过20条，避免超时）
     *
     * @param texts 文本列表
     * @return 对应的向量列表（顺序与输入一致）
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建请求体（OpenAI 兼容格式）
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getEmbed().getModelName());
        requestBody.put("input", texts);
        requestBody.put("dimensions", config.getEmbed().getDimensions());

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getEmbed().getApiKey());

        String url = config.getEmbed().getBaseUrl() + "/embeddings";
        log.info("调用 Embedding API: url={}, model={}, 文本数={}", url, config.getEmbed().getModelName(), texts.size());

        try {
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

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
            List<float[]> vectors = new ArrayList<>(dataArray.size());
            // 硅基流动返回的 data 数组带 index 字段，先按 index 排序
            List<JSONObject> sortedData = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                sortedData.add(dataArray.getJSONObject(i));
            }
            sortedData.sort(Comparator.comparingInt(o -> o.getIntValue("index")));

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
                log.info("Embedding 完成: 文本数={}, tokens={}", texts.size(), usage.getIntValue("total_tokens"));
            }

            return vectors;

        } catch (Exception e) {
            log.error("Embedding API 调用失败", e);
            throw new RuntimeException("Embedding 失败: " + e.getMessage(), e);
        }
    }
}
