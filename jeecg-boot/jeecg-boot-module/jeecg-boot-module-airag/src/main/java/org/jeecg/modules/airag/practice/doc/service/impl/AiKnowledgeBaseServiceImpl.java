package org.jeecg.modules.airag.practice.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentChunkMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiKnowledgeBaseMapper;
import org.jeecg.modules.airag.practice.doc.service.IAiKnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI知识库 Service 实现
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@Service
public class AiKnowledgeBaseServiceImpl
        extends ServiceImpl<AiKnowledgeBaseMapper, AiKnowledgeBase>
        implements IAiKnowledgeBaseService {

    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @Resource
    private AiDocumentChunkMapper aiDocumentChunkMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteWithDocuments(String kbId) {
        // 1. 查出该知识库下所有文档ID
        List<AiDocument> docs = aiDocumentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getKnowledgeBaseId, kbId)
        );

        if (!docs.isEmpty()) {
            List<String> docIds = docs.stream()
                    .map(AiDocument::getId)
                    .collect(Collectors.toList());

            // 2. 批量删除所有分片
            long deletedChunks = aiDocumentChunkMapper.delete(
                    new LambdaQueryWrapper<AiDocumentChunk>()
                            .in(AiDocumentChunk::getDocumentId, docIds)
            );
            log.info("删除知识库分片: kbId={}, 分片数={}", kbId, deletedChunks);

            // 3. 删除所有文档
            aiDocumentMapper.delete(
                    new LambdaQueryWrapper<AiDocument>()
                            .eq(AiDocument::getKnowledgeBaseId, kbId)
            );
        }

        // 4. 删除知识库本身
        this.removeById(kbId);
        log.info("删除知识库: kbId={}, 文档数={}", kbId, docs.size());

        return docs.size();
    }
}
