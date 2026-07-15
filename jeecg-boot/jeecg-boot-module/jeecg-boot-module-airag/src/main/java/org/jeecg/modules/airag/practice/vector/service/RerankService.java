package org.jeecg.modules.airag.practice.vector.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Rerank 重排序服务
 *
 * 使用 Cross-Encoder 模型（bge-reranker-v2-m3）对候选文档精排。
 * 原理：Bi-Encoder（Embedding）分别编码 query 和 doc，用点积算相似度（快但粗糙）；
 *       Cross-Encoder 把 (query, doc) 拼接后一起编码，直接输出相关性分数（慢但准确）。
 *
 * 流程：kNN 粗召回 top20 → Rerank 精排 → 返回 top5
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@Service
public class RerankService {

    @Resource
    private PracticeVectorConfig config;

    @Resource
    private RestTemplate practiceEmbedRestTemplate;

    /**
     * 对候选文档重排序
     *
     * @param query      用户查询
     * @param documents  候选文档文本列表（来自 kNN 粗召回）
     * @param topN       返回前 N 条
     * @return 排序后的结果列表，index 指向 documents 的原始下标
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        // 未配置或未启用 Rerank，降级返回原始顺序
        PracticeVectorConfig.RerankProperties rerankConfig = config.getRerank();
        if (!rerankConfig.isEnabled()) {
            log.debug("Rerank 未启用，返回原始顺序");
            return IntStream.range(0, Math.min(topN, documents.size()))
                    .mapToObj(i -> new RerankResult(i, 1.0f - i * 0.01f))
                    .collect(Collectors.toList());
        }

        // 构建请求体
        JSONObject body = new JSONObject();
        body.put("model", rerankConfig.getModelName());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", topN);
        body.put("return_documents", false);

        // 请求头（复用 Embedding 的 API Key，硅基流动通用）
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(config.getEmbed().getApiKey());

        String url = config.getEmbed().getBaseUrl() + "/rerank";
        log.info("调用 Rerank API: url={}, model={}, queryLength={}, 文档数={}",
                url, rerankConfig.getModelName(), query == null ? 0 : query.length(), documents.size());

        try {
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);
            ResponseEntity<String> resp = practiceEmbedRestTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (resp.getStatusCode() != HttpStatus.OK || resp.getBody() == null) {
                throw new RuntimeException("Rerank API 返回异常: status=" + resp.getStatusCode());
            }

            JSONObject respJson = JSON.parseObject(resp.getBody());
            JSONArray results = respJson.getJSONArray("results");

            if (results == null) {
                throw new RuntimeException("Rerank API 返回 results 为空");
            }

            List<RerankResult> rerankResults = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                rerankResults.add(new RerankResult(
                        item.getIntValue("index"),
                        item.getFloatValue("relevance_score")
                ));
            }

            log.info("Rerank 完成: 输入={}, 返回={}", documents.size(), rerankResults.size());
            return rerankResults;

        } catch (Exception e) {
            // 降级：返回原始顺序（按 kNN 分数）
            log.error("Rerank API 调用失败，降级返回原始顺序", e);
            return IntStream.range(0, Math.min(topN, documents.size()))
                    .mapToObj(i -> new RerankResult(i, 1.0f - i * 0.01f))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Rerank 结果
     */
    @Data
    @AllArgsConstructor
    public static class RerankResult {
        /** 对应输入 documents 的下标 */
        private int index;
        /** 相关性分数 0~1 */
        private float score;
    }
}
