package org.jeecg.modules.airag.practice.doc.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.mapper.AiKnowledgeBaseMapper;
import org.jeecg.modules.airag.practice.doc.service.IAiDocumentChunkService;
import org.jeecg.modules.airag.practice.doc.vo.DocumentUploadResultVO;
import org.jeecg.modules.airag.practice.security.KnowledgeAccessService;
import org.jeecg.modules.airag.practice.security.PracticeSecurityContext;
import org.jeecg.modules.airag.practice.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

/**
 * AI文档管理 Controller
 *
 * 接口清单：
 * - POST   /practice/doc/upload                上传文档 → 解析 → 切分 → 存储
 * - GET    /practice/doc/chunks/{documentId}   查询某文档的所有分片
 * - DELETE /practice/doc/{documentId}          删除某文档及其所有分片
 * - GET    /practice/doc/kb/list               查询所有知识库
 * - GET    /practice/doc/kb/{kbId}/docs        查询某知识库下的文档列表
 *
 * @IgnoreAuth 方便练习测试，生产环境应移除
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Slf4j
@RestController
@RequestMapping("/practice/doc")
@Tag(name = "AI文档管理", description = "知识库、文档、分片的完整管理链路")
public class AiDocumentChunkController {

    @Resource
    private IAiDocumentChunkService aiDocumentChunkService;

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Resource
    private PracticeSecurityContext practiceSecurityContext;

    @Resource
    private KnowledgeAccessService knowledgeAccessService;

    // ==================== 文档上传与分片 ====================

    /**
     * 上传 Markdown 文档
     *
     * 流程：校验文件 → 确保知识库存在 → 创建 AiDocument(pending) → 保存原始文件
     *      → MarkdownParser 解析切分 → 批量入库 → 更新状态 → 返回结果
     *
     * @param file            Markdown 文件（.md / .markdown / .txt）
     * @param knowledgeBaseId 知识库ID（可选，为空则使用默认知识库）
     * @return 上传结果，含分片统计和前5条预览
     */
    @RequiresPermissions("practice:doc:upload")
    @PostMapping("/upload")
    @Operation(summary = "上传文档并解析切分")
    public Result<DocumentUploadResultVO> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId) {
        LoginUser loginUser = practiceSecurityContext.requireUser();
        if (StringUtils.isBlank(knowledgeBaseId)) {
            return Result.error("knowledgeBaseId 不能为空");
        }
        knowledgeAccessService.requireManageableKnowledgeBase(knowledgeBaseId, loginUser);
        return Result.OK(aiDocumentChunkService.uploadAndParse(file, knowledgeBaseId, loginUser.getId()));
    }

    /**
     * 查询某文档的所有分片
     */
    @RequiresPermissions("practice:doc:list")
    @GetMapping("/chunks/{documentId}")
    @Operation(summary = "查询文档分片列表")
    public Result<List<AiDocumentChunk>> listChunks(@PathVariable String documentId) {
        LoginUser user = practiceSecurityContext.requireUser();
        knowledgeAccessService.requireReadableDocument(documentId, user);
        return Result.OK(aiDocumentChunkService.listByDocumentId(documentId));
    }

    /**
     * 删除某文档及其所有分片
     */
    @RequiresPermissions("practice:doc:delete")
    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除文档及其所有分片")
    public Result<Integer> deleteDocument(@PathVariable String documentId) {
        LoginUser user = practiceSecurityContext.requireUser();
        knowledgeAccessService.requireManageableDocument(documentId, user);
        return Result.OK(aiDocumentChunkService.deleteDocumentAndChunks(documentId));
    }

    // ==================== 知识库管理 ====================

    /**
     * 查询所有知识库
     */
    @RequiresPermissions("practice:doc:list")
    @GetMapping("/kb/list")
    @Operation(summary = "查询所有知识库")
    public Result<List<AiKnowledgeBase>> listKnowledgeBases() {
        LoginUser user = practiceSecurityContext.requireUser();
        List<String> ids = knowledgeAccessService.readableKnowledgeBaseIds(user);
        List<AiKnowledgeBase> list = ids.isEmpty()
                ? Collections.emptyList()
                : aiKnowledgeBaseMapper.selectByIds(ids);
        return Result.OK(list);
    }

    /**
     * 查询某知识库下的文档列表
     */
    @RequiresPermissions("practice:doc:list")
    @GetMapping("/kb/{kbId}/docs")
    @Operation(summary = "查询知识库下的文档列表")
    public Result<List<AiDocument>> listDocsByKnowledgeBase(@PathVariable String kbId) {
        LoginUser user = practiceSecurityContext.requireUser();
        knowledgeAccessService.requireReadableKnowledgeBase(kbId, user);
        List<AiDocument> docs = aiDocumentChunkService.listDocumentsByKnowledgeBase(kbId);
        return Result.OK(docs);
    }
}
