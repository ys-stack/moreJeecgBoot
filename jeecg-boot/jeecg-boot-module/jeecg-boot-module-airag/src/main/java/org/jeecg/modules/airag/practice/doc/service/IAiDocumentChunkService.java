package org.jeecg.modules.airag.practice.doc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.vo.DocumentUploadResultVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * AI文档分片 Service 接口
 * 整合 知识库 → 文档 → 分片 的完整链路
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
public interface IAiDocumentChunkService extends IService<AiDocumentChunk> {

    /**
     * 上传文档 → 解析 → 切分 → 存储
     *
     * 完整流程：
     * 1. 校验文件（.md/.txt，≤ 10MB）
     * 2. 确保知识库存在（无则创建默认知识库）
     * 3. 创建 AiDocument 记录（status=pending）
     * 4. 保存原始文件到磁盘
     * 5. MarkdownParser 解析切分
     * 6. 批量插入 AiDocumentChunk
     * 7. 更新 AiDocument（status=completed，chunkCount，totalChars）
     * 8. 更新 AiKnowledgeBase 的冗余计数
     *
     * @param file            Markdown 文件
     * @param knowledgeBaseId 知识库ID（为空则使用默认知识库）
     * @return 上传结果（含分片统计）
     */
    DocumentUploadResultVO uploadAndParse(MultipartFile file, String knowledgeBaseId, String operatorId);

    /**
     * 查询某文档的所有分片（按 chunkIndex 排序）
     *
     * @param documentId 文档ID
     * @return 分片列表
     */
    List<AiDocumentChunk> listByDocumentId(String documentId);

    /**
     * 删除某文档及其所有分片
     *
     * @param documentId 文档ID
     * @return 删除的分片数量
     */
    int deleteDocumentAndChunks(String documentId);

    /**
     * 查询某知识库下的所有文档
     *
     * @param knowledgeBaseId 知识库ID
     * @return 文档列表
     */
    List<AiDocument> listDocumentsByKnowledgeBase(String knowledgeBaseId);
}
