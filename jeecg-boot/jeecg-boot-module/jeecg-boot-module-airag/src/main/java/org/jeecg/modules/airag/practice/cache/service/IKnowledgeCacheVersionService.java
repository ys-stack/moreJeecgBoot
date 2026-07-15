package org.jeecg.modules.airag.practice.cache.service;

import java.util.List;

public interface IKnowledgeCacheVersionService {

    long getVersion(String knowledgeBaseId);

    String buildFingerprint(List<String> knowledgeBaseIds);

    void bumpVersion(String knowledgeBaseId);

    void publishDeleted(String knowledgeBaseId);

    void evictLocalAndRedis(String knowledgeBaseId);
}
