package org.jeecg.modules.airag.practice.cache.service;

import org.jeecg.modules.airag.practice.cache.context.RagAnswerCacheContext;
import org.jeecg.modules.airag.practice.cache.entity.FaqCacheItem;

public interface IRagAnswerCacheService {

    FaqCacheItem get(RagAnswerCacheContext context, String question);

    void put(RagAnswerCacheContext context, String question, FaqCacheItem item);

    void evictLocalByKnowledgeBaseId(String knowledgeBaseId);
}
