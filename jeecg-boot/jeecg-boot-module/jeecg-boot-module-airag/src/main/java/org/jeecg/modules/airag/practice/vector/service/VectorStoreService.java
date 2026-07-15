package org.jeecg.modules.airag.practice.vector.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.vector.cache.EmbeddingCacheContext;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.jeecg.modules.airag.practice.vector.vo.VectorSearchResultVO;
import org.jeecg.modules.airag.practice.aspect.annotation.CircuitBreaker;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量存储服务（ES 8.x REST API）
 *
 * 通过 RestTemplate + fastjson 直接与 ES 通信，不引入 ES Java 客户端依赖。
 * 核心操作：
 * - ensureIndex(): 检查/创建索引，mapping 含 dense_vector(1024, cosine, HNSW)
 * - storeChunks(): 批量写入分片向量
 * - search(): 向量检索（kNN）
 * - deleteByDocumentId(): 按文档ID清理向量
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@Service
public class VectorStoreService {

    @Resource
    private PracticeVectorConfig config;

    @Resource
    private RestTemplate practiceEsRestTemplate;

    @Resource
    private EmbeddingService embeddingService;

    @Resource
    private RerankService rerankService;

    // 限制向 ES 物理写入的最大并发 HTTP 连接数，防止高并发导致 ES 拒绝（429）
    private final java.util.concurrent.Semaphore writeSemaphore = new java.util.concurrent.Semaphore(2);

    // ==================== 索引管理 ====================

    /**
     * 确保 ES 索引存在，不存在则创建
     * mapping 包含 dense_vector 字段用于向量检索
     */
    public void ensureIndex() {
        String indexName = config.getEs().getIndexName();
        String url = "http://" + config.getEs().getClusterNodes() + "/" + indexName;

        // 检查索引是否存在
        try {
            ResponseEntity<String> resp = practiceEsRestTemplate.exchange(url, HttpMethod.HEAD, null, String.class);
            if (resp.getStatusCode() == HttpStatus.OK) {
                log.info("ES 索引已存在: {}", indexName);
                return;
            }
        } catch (Exception e) {
            // 404 说明索引不存在，需要创建
            log.info("ES 索引不存在，准备创建: {}", indexName);
        }

        // 创建索引（settings + mapping）
        JSONObject body = new JSONObject();

        // settings: 3分片 1副本（和3节点集群匹配）
        JSONObject settings = new JSONObject();
        settings.put("number_of_shards", 3);
        settings.put("number_of_replicas", 1);
        body.put("settings", settings);

        // mapping
        JSONObject mappings = new JSONObject();
        JSONObject properties = new JSONObject();

        properties.put("chunk_id", typeMapping("keyword"));
        properties.put("document_id", typeMapping("keyword"));
        properties.put("knowledge_base_id", typeMapping("keyword"));
        properties.put("chunk_text", textMapping("standard"));
        properties.put("chunk_vector", denseVectorMapping());
        properties.put("heading_path", typeMapping("keyword"));
        properties.put("chunk_index", typeMapping("integer"));
        properties.put("chunk_type", typeMapping("keyword"));
        properties.put("source_file_name", typeMapping("keyword"));

        // fastjson 默认将 Date 序列化为 "yyyy-MM-dd HH:mm:ss.SSS" 格式
        JSONObject createdAtMapping = new JSONObject();
        createdAtMapping.put("type", "date");
        createdAtMapping.put("format", "yyyy-MM-dd HH:mm:ss.SSS||yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis");
        properties.put("created_at", createdAtMapping);

        JSONObject mappingsWrapper = new JSONObject();
        mappingsWrapper.put("properties", properties);
        body.put("mappings", mappingsWrapper);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        try {
            practiceEsRestTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
            log.info("ES 索引创建成功: {}, dims={}", indexName, config.getEmbed().getDimensions());
        } catch (Exception e) {
            log.error("ES 索引创建失败", e);
            throw new RuntimeException("ES 索引创建失败: " + e.getMessage(), e);
        }
    }

    // ==================== 向量写入 ====================

    /**
     * 将分片文本向量化后批量写入 ES
     *
     * @param documentId      文档ID
     * @param knowledgeBaseId 知识库ID
     * @param chunks          分片列表（MySQL 中的记录）
     * @return 成功写入的数量
     */
    public int vectorizeAndStore(
            String documentId,
            String knowledgeBaseId,
            List<AiDocumentChunk> chunks,
            String tenantId) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }

        ensureIndex();

        // 提取文本列表
        List<String> texts = chunks.stream()
                .map(AiDocumentChunk::getContent)
                .collect(Collectors.toList());

        // 批量 Embedding（分批，每批最多20条，避免超时）
        List<float[]> allVectors = batchEmbed(
                texts,
                20,
                EmbeddingCacheContext.tenant(tenantId)
        );

        // 构建 ES bulk 请求
        StringBuilder bulkBody = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            AiDocumentChunk chunk = chunks.get(i);
            float[] vector = allVectors.get(i);

            // action: index（用 chunk_id 作为 ES _id，方便后续更新）
            JSONObject action = new JSONObject();
            JSONObject indexAction = new JSONObject();
            indexAction.put("_index", config.getEs().getIndexName());
            indexAction.put("_id", chunk.getId());
            action.put("index", indexAction);
            bulkBody.append(action.toJSONString()).append("\n");

            // document: 分片元数据 + 向量
            JSONObject doc = new JSONObject();
            doc.put("chunk_id", chunk.getId());
            doc.put("document_id", documentId);
            doc.put("knowledge_base_id", knowledgeBaseId);
            doc.put("chunk_text", chunk.getContent());
            doc.put("chunk_vector", floatArrayToList(vector));
            doc.put("heading_path", chunk.getHeading());
            doc.put("chunk_index", chunk.getChunkIndex());
            doc.put("chunk_type", chunk.getChunkType());
            doc.put("source_file_name", chunk.getSourceFileName());
            doc.put("created_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date()));
            bulkBody.append(doc.toJSONString()).append("\n");
        }

        // 发送 bulk 请求（必须指定 UTF-8 编码，否则中文会变成问号）
        String url = "http://" + config.getEs().getClusterNodes() + "/_bulk";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "x-ndjson", java.nio.charset.StandardCharsets.UTF_8));
        HttpEntity<String> entity = new HttpEntity<>(bulkBody.toString(), headers);

        try {
            writeSemaphore.acquire();
            try {
                ResponseEntity<String> resp = practiceEsRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                JSONObject result = JSON.parseObject(resp.getBody());

                if (result.getBooleanValue("errors")) {
                    // 部分失败，记录详情
                    JSONArray items = result.getJSONArray("items");
                    int errorCount = 0;
                    for (int i = 0; i < items.size(); i++) {
                        JSONObject item = items.getJSONObject(i).getJSONObject("index");
                        if (item != null && item.getInteger("status") >= 400) {
                            errorCount++;
                            log.warn("ES bulk 写入失败: chunk={}, status={}, error={}",
                                    chunks.get(i).getId(), item.getInteger("status"),
                                    item.getJSONObject("error"));
                        }
                    }
                    log.error("ES bulk 写入部分失败: 总数={}, 失败数={}", chunks.size(), errorCount);
                    throw new IllegalStateException("ES bulk 写入部分失败: " + errorCount + "/" + chunks.size());
                }

                log.info("ES 向量写入成功: documentId={}, chunks={}", documentId, chunks.size());
                return chunks.size();
            } finally {
                writeSemaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ES 向量写入被中断", e);
        } catch (Exception e) {
            log.error("ES bulk 写入失败", e);
            throw new RuntimeException("ES 向量写入失败: " + e.getMessage(), e);
        }
    }

    // ==================== 向量检索 ====================

    /**
     * 向量检索：query → Embedding → ES kNN 粗召回 → Rerank 精排 → topK
     *
     * 两阶段检索：
     * 1. kNN 粗召回：取 topK * 4 候选（快但不够准）
     * 2. Rerank 精排：Cross-Encoder 对 (query, doc) 打分（慢但准确）
     *
     * @param query           用户查询文本
     * @param topK            返回条数
     * @param knowledgeBaseId 知识库ID（可选，为空则搜索全部）
     * @return 相似分片列表（按 Rerank 分数降序）
     */
    @CircuitBreaker(value = "es_vector_search", failureThreshold = 5, timeout = 10000)
    public List<VectorSearchResultVO> search(String query, int topK, String knowledgeBaseId, String tenantId) {
        // 构建单知识库 filter（term）
        JSONObject filter = null;
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            JSONObject term = new JSONObject();
            term.put("knowledge_base_id", knowledgeBaseId);
            filter = new JSONObject();
            filter.put("term", term);
        }
        return executeSearch(
                query,
                topK,
                filter,
                "向量检索",
                EmbeddingCacheContext.tenant(tenantId)
        );
    }

    /**
     * 向量检索：支持多知识库过滤（权限过滤场景）
     *
     * @param query             用户查询文本
     * @param topK              返回条数
     * @param knowledgeBaseIds  允许访问的知识库ID列表（为空则搜索全部）
     * @return 相似分片列表
     */
    @CircuitBreaker(value = "es_vector_search_multi_kb", failureThreshold = 5, timeout = 10000)
    public List<VectorSearchResultVO> searchByKnowledgeBaseIds(
            String query,
            int topK,
            List<String> knowledgeBaseIds,
            String tenantId) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return Collections.emptyList();
        }
        if (knowledgeBaseIds.size() == 1) {
            return search(query, topK, knowledgeBaseIds.get(0), tenantId);
        }
        // 多知识库用 terms filter
        JSONObject terms = new JSONObject();
        terms.put("knowledge_base_id", knowledgeBaseIds);
        JSONObject filter = new JSONObject();
        filter.put("terms", terms);
        return executeSearch(
                query,
                topK,
                filter,
                "多知识库向量检索",
                EmbeddingCacheContext.tenant(tenantId)
        );
    }

    /**
     * 通用检索流程：Embedding → kNN 粗召回 → Rerank 精排
     *
     * @param query   用户查询文本
     * @param topK    返回条数
     * @param filter  ES filter（可为 null，null 则搜索全部）
     * @param logTag  日志标签
     * @return 相似分片列表
     */
    private List<VectorSearchResultVO> executeSearch(
            String query,
            int topK,
            JSONObject filter,
            String logTag,
            EmbeddingCacheContext embeddingContext) {
        ensureIndex();

        float[] queryVector = embeddingService.embed(query, embeddingContext);

        int recallSize = topK * 4;
        List<VectorSearchResultVO> candidates = knnSearch(queryVector, recallSize, filter);

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Rerank 精排
        List<String> candidateTexts = candidates.stream()
                .map(VectorSearchResultVO::getContent)
                .collect(Collectors.toList());
        List<RerankService.RerankResult> rerankResults = rerankService.rerank(query, candidateTexts, topK);

        List<VectorSearchResultVO> finalResults = new ArrayList<>();
        for (RerankService.RerankResult rr : rerankResults) {
            if (rr.getIndex() < candidates.size()) {
                VectorSearchResultVO candidate = candidates.get(rr.getIndex());
                candidate.setScore(rr.getScore());
                finalResults.add(candidate);
            }
        }

        log.info("{}完成: queryLength={}, kNN召回={}, Rerank返回={}",
                logTag, query == null ? 0 : query.length(), candidates.size(), finalResults.size());
        return finalResults;
    }

    /**
     * kNN 粗召回（纯向量检索，不做 Rerank）
     *
     * @param queryVector 查询向量
     * @param size        召回数量
     * @param filter      ES filter（可为 null）
     * @return 候选分片列表
     */
    private List<VectorSearchResultVO> knnSearch(float[] queryVector, int size, JSONObject filter) {
        JSONObject body = new JSONObject();

        JSONObject knn = new JSONObject();
        knn.put("field", "chunk_vector");
        knn.put("query_vector", floatArrayToList(queryVector));
        knn.put("k", size);
        knn.put("num_candidates", size * 10);

        if (filter != null) {
            knn.put("filter", filter);
        }

        body.put("knn", knn);
        body.put("_source", List.of("chunk_id", "document_id", "knowledge_base_id",
                "chunk_text", "heading_path", "chunk_index", "source_file_name"));
        body.put("size", size);

        String url = "http://" + config.getEs().getClusterNodes() + "/"
                + config.getEs().getIndexName() + "/_search";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        try {
            ResponseEntity<String> resp = practiceEsRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            JSONObject hits = result.getJSONObject("hits");

            if (hits == null || hits.getJSONArray("hits") == null) {
                return Collections.emptyList();
            }

            List<VectorSearchResultVO> results = new ArrayList<>();
            JSONArray hitsArray = hits.getJSONArray("hits");
            for (int i = 0; i < hitsArray.size(); i++) {
                JSONObject hit = hitsArray.getJSONObject(i);
                JSONObject source = hit.getJSONObject("_source");
                float score = hit.getFloatValue("_score");

                results.add(VectorSearchResultVO.builder()
                        .chunkId(source.getString("chunk_id"))
                        .documentId(source.getString("document_id"))
                        .knowledgeBaseId(source.getString("knowledge_base_id"))
                        .content(source.getString("chunk_text"))
                        .headingPath(source.getString("heading_path"))
                        .score(score)
                        .sourceFileName(source.getString("source_file_name"))
                        .chunkIndex(source.getInteger("chunk_index"))
                        .build());
            }

            return results;

        } catch (Exception e) {
            log.error("ES kNN 粗召回失败", e);
            throw new RuntimeException("向量检索失败: " + e.getMessage(), e);
        }
    }

    // ==================== 向量删除 ====================

    /**
     * 按文档ID删除 ES 中的向量数据（文档删除时同步调用）
     *
     * @param documentId 文档ID
     * @return 删除的文档数
     */
    public long deleteByDocumentId(String documentId) {
        String url = "http://" + config.getEs().getClusterNodes() + "/"
                + config.getEs().getIndexName() + "/_delete_by_query";

        JSONObject body = new JSONObject();
        JSONObject query = new JSONObject();
        JSONObject term = new JSONObject();
        term.put("document_id", documentId);
        query.put("term", term);
        body.put("query", query);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        try {
            ResponseEntity<String> resp = practiceEsRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            long deleted = result.getLongValue("deleted");
            log.info("ES 向量删除完成: documentId={}, deleted={}", documentId, deleted);
            return deleted;
        } catch (HttpClientErrorException.NotFound e) {
            // 索引不存在等价于没有待删除数据，是幂等成功。
            log.info("ES 索引不存在，无需删除向量: documentId={}", documentId);
            return 0;
        } catch (Exception e) {
            log.error("ES 向量删除失败: documentId={}", documentId, e);
            throw new RuntimeException("ES 向量删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询某文档在 ES 中的向量数量
     */
    public long countByDocumentId(String documentId) {
        String url = "http://" + config.getEs().getClusterNodes() + "/"
                + config.getEs().getIndexName() + "/_count";

        JSONObject body = new JSONObject();
        JSONObject query = new JSONObject();
        JSONObject term = new JSONObject();
        term.put("document_id", documentId);
        query.put("term", term);
        body.put("query", query);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        try {
            ResponseEntity<String> resp = practiceEsRestTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JSONObject result = JSON.parseObject(resp.getBody());
            return result.getLongValue("count");
        } catch (HttpClientErrorException.NotFound e) {
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("查询 ES 向量数量失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 分批 Embedding，每批 maxSize 条
     */
    private List<float[]> batchEmbed(
            List<String> texts,
            int maxSize,
            EmbeddingCacheContext embeddingContext) {
        List<float[]> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += maxSize) {
            int end = Math.min(i + maxSize, texts.size());
            List<String> batch = texts.subList(i, end);
            log.info("Embedding 批次 {}/{}: {}条", (i / maxSize) + 1,
                    (int) Math.ceil((double) texts.size() / maxSize), batch.size());
            List<float[]> batchVectors = embeddingService.embedBatch(batch, embeddingContext);
            allVectors.addAll(batchVectors);
        }
        return allVectors;
    }

    /** float[] → List<Float>（ES JSON 序列化需要） */
    private List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private JSONObject typeMapping(String type) {
        JSONObject m = new JSONObject();
        m.put("type", type);
        return m;
    }

    private JSONObject textMapping(String analyzer) {
        JSONObject m = new JSONObject();
        m.put("type", "text");
        m.put("analyzer", analyzer);
        return m;
    }

    /** dense_vector mapping: 1024维, cosine相似度, HNSW索引 */
    private JSONObject denseVectorMapping() {
        JSONObject m = new JSONObject();
        m.put("type", "dense_vector");
        m.put("dims", config.getEmbed().getDimensions());
        m.put("index", true);
        m.put("similarity", "cosine");
        return m;
    }

    //update-begin---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
    /**
     * search 降级方法（发生熔断或检索故障时触发，返回空列表）
     */
    public List<VectorSearchResultVO> searchFallback(String query, int topK, String knowledgeBaseId) {
        log.warn("[熔断器降级] ES 单知识库检索触发降级，返回空列表. query={}", query);
        return Collections.emptyList();
    }

    /**
     * searchByKnowledgeBaseIds 降级方法（发生熔断或检索故障时触发，返回空列表）
     */
    public List<VectorSearchResultVO> searchByKnowledgeBaseIdsFallback(String query, int topK, List<String> knowledgeBaseIds) {
        log.warn("[熔断器降级] ES 多知识库检索触发降级，返回空列表. query={}", query);
        return Collections.emptyList();
    }
    //update-end---author:ys ---date:2026-07-10  for：MySQL-ES异步同步-----------
}
