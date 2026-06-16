package org.jeecg.modules.airag.practice.doc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;

/**
 * AI知识库 Service
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
public interface IAiKnowledgeBaseService extends IService<AiKnowledgeBase> {

    /**
     * 删除知识库及其下所有文档和分片
     *
     * @param kbId 知识库ID
     * @return 删除的文档数
     */
    int deleteWithDocuments(String kbId);
}
