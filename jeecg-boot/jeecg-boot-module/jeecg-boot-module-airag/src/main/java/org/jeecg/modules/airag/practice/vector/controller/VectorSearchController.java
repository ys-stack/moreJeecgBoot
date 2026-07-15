package org.jeecg.modules.airag.practice.vector.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.cache.service.IKnowledgeCacheVersionService;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.doc.service.IAiDocumentChunkService;
import org.jeecg.modules.airag.practice.doc.service.IAiKnowledgeBaseService;
import org.jeecg.modules.airag.practice.vector.service.VectorStoreService;
import org.jeecg.modules.airag.practice.vector.vo.VectorSearchRequestVO;
import org.jeecg.modules.airag.practice.vector.vo.VectorSearchResultVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 向量检索 Controller
 *
 * 接口清单：
 * - POST   /practice/vector/vectorize/{documentId}  对文档分片做 Embedding 并写入 ES
 * - POST   /practice/vector/search                   向量检索（query → topK chunk）
 * - DELETE /practice/vector/{documentId}              删除文档的向量数据
 * - GET    /practice/vector/status/{documentId}       查看文档向量状态
 *
 * @IgnoreAuth 方便练习测试，生产环境应移除
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@RestController
@RequestMapping("/practice/vector")
@Tag(name = "向量检索", description = "Embedding + ES 向量存储 + kNN 检索")
public class VectorSearchController {

    @Resource
    private VectorStoreService vectorStoreService;

    @Resource
    private IAiDocumentChunkService aiDocumentChunkService;

    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @Resource
    private IAiKnowledgeBaseService knowledgeBaseService;

    @Resource
    private IKnowledgeCacheVersionService knowledgeCacheVersionService;

    /**
     * 对指定文档的分片做向量化并写入 ES
     *
     * 流程：查 MySQL 拿 chunks → 批量 Embedding → bulk 写入 ES
     */
    @PostMapping("/vectorize/{documentId}")
    @Operation(summary = "文档分片向量化并写入ES")
    public Result<Integer> vectorize(@PathVariable String documentId) {
        try {
            // 1. 查文档信息（获取 knowledgeBaseId）
            AiDocument doc = aiDocumentMapper.selectById(documentId);
            if (doc == null) {
                return Result.error("文档不存在: " + documentId);
            }

            // 2. 查文档的所有分片
            List<AiDocumentChunk> chunks = aiDocumentChunkService.listByDocumentId(documentId);
            if (chunks.isEmpty()) {
                return Result.error("文档无分片数据，请先上传解析: " + documentId);
            }

            // 3. 向量化 + 写入 ES
            int count = vectorStoreService.vectorizeAndStore(
                    documentId,
                    doc.getKnowledgeBaseId(),
                    chunks,
                    doc.getTenantId()
            );
            if (count != chunks.size()) {
                return Result.error("向量化写入数量不一致，期望 " + chunks.size() + " 条，实际 " + count + " 条");
            }
            if (aiDocumentMapper.selectById(documentId) == null) {
                vectorStoreService.deleteByDocumentId(documentId);
                return Result.error("向量化期间文档已删除，已补偿清理 ES 数据");
            }
            // 4. 更新文档状态（标记已向量化）
            doc.setStatus("vectorized");
            aiDocumentMapper.updateById(doc);
            knowledgeCacheVersionService.bumpVersion(doc.getKnowledgeBaseId());

            return Result.OK("向量化完成，写入 " + count + " 条向量", count);
        } catch (Exception e) {
            log.error("向量化失败: documentId={}", documentId, e);
            return Result.error("向量化失败: " + e.getMessage());
        }
    }

    /**
     * 向量检索接口
     *
     * 流程：query Embedding → ES kNN 搜索 → 返回 topK chunk
     */
    @PostMapping("/search")
    @Operation(summary = "向量语义检索")
    public Result<List<VectorSearchResultVO>> search(@RequestBody VectorSearchRequestVO request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return Result.error("查询文本不能为空");
        }
        if (request.getKnowledgeBaseId() == null || request.getKnowledgeBaseId().isBlank()) {
            return Result.error("直接向量检索必须指定 knowledgeBaseId");
        }
        try {
            AiKnowledgeBase knowledgeBase = knowledgeBaseService.getById(request.getKnowledgeBaseId());
            if (knowledgeBase == null) {
                return Result.error("知识库不存在");
            }
            List<VectorSearchResultVO> results = vectorStoreService.search(
                    request.getQuery(),
                    request.getTopK(),
                    request.getKnowledgeBaseId(),
                    knowledgeBase.getTenantId());
            return Result.OK(results);
        } catch (Exception e) {
            log.error("向量检索失败: query={}", request.getQuery(), e);
            return Result.error("向量检索失败: " + e.getMessage());
        }
    }

    /**
     * 删除某文档的向量数据
     */
    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除文档向量数据")
    public Result<Long> deleteVectors(@PathVariable String documentId) {
        try {
            AiDocument doc = aiDocumentMapper.selectById(documentId);
            long deleted = vectorStoreService.deleteByDocumentId(documentId);
            // 直接删除 ES 向量同样会改变知识库内容，必须让旧答案缓存立即失效。
            if (doc != null) {
                knowledgeCacheVersionService.bumpVersion(doc.getKnowledgeBaseId());
            }
            return Result.OK("已删除 " + deleted + " 条向量", deleted);
        } catch (Exception e) {
            log.error("向量删除失败: documentId={}", documentId, e);
            return Result.error("向量删除失败: " + e.getMessage());
        }
    }

    /**
     * 查看某文档在 ES 中的向量状态
     */
    @GetMapping("/status/{documentId}")
    @Operation(summary = "查看文档向量状态")
    public Result<Long> vectorStatus(@PathVariable String documentId) {
        long count = vectorStoreService.countByDocumentId(documentId);
        return Result.OK("ES 中该文档有 " + count + " 条向量", count);
    }
}
