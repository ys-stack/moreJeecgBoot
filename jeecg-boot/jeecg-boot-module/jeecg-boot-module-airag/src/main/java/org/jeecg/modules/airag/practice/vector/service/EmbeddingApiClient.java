package org.jeecg.modules.airag.practice.vector.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 硅基流动 Embedding API 客户端。
 *
 * <p>单独拆成 Spring Bean，确保 {@link Retryable} 通过代理生效，避免同类内部调用导致重试失效。</p>
 */
@Slf4j
@Service
public class EmbeddingApiClient {

    @Resource
    private PracticeVectorConfig config;

    @Resource
    @Qualifier("practiceEmbedRestTemplate")
    private RestTemplate restTemplate;

    @Retryable(
            retryFor = RuntimeException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000L, multiplier = 2.0)
    )
    public List<float[]> embedBatch(List<String> texts) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getEmbed().getModelName());
        requestBody.put("input", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getEmbed().getApiKey());

        String url = config.getEmbed().getBaseUrl() + "/embeddings";
        HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);
        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                String.class
        );

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
            throw new RuntimeException("Embedding API 返回异常: status=" + response.getStatusCode());
        }

        JSONObject responseJson = JSON.parseObject(response.getBody());
        JSONArray dataArray = responseJson.getJSONArray("data");
        if (dataArray == null || dataArray.size() != texts.size()) {
            throw new RuntimeException(
                    "Embedding 返回数量不匹配，期望=" + texts.size()
                            + "，实际=" + (dataArray == null ? 0 : dataArray.size())
            );
        }

        List<JSONObject> sortedItems = new ArrayList<>(dataArray.size());
        for (int i = 0; i < dataArray.size(); i++) {
            sortedItems.add(dataArray.getJSONObject(i));
        }
        sortedItems.sort(Comparator.comparingInt(item -> item.getIntValue("index")));

        List<float[]> vectors = new ArrayList<>(sortedItems.size());
        for (JSONObject item : sortedItems) {
            JSONArray embedding = item.getJSONArray("embedding");
            if (embedding == null || embedding.isEmpty()) {
                throw new RuntimeException("Embedding API 返回空向量");
            }

            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = embedding.getFloatValue(i);
            }
            vectors.add(vector);
        }

        JSONObject usage = responseJson.getJSONObject("usage");
        if (usage != null) {
            log.info("Embedding API 调用成功: count={}, totalTokens={}",
                    texts.size(), usage.getIntValue("total_tokens"));
        }
        return vectors;
    }

    @Recover
    public List<float[]> recover(RuntimeException exception, List<String> texts) {
        log.error("Embedding API 重试耗尽: textCount={}", texts.size(), exception);
        throw new RuntimeException("Embedding API 重试耗尽: " + exception.getMessage(), exception);
    }
}
