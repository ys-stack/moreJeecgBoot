package org.jeecg.modules.airag.practice.cache.context;

import java.util.List;

/**
 * RAG 答案缓存作用域。
 *
 * <p>这里不使用 sessionId。仅缓存第一轮、无历史上下文的 FAQ 问题，
 * 同一租户、相同知识库权限范围和版本可以安全复用。</p>
 */
public record RagAnswerCacheContext(
        String tenantId,
        List<String> knowledgeBaseIds,
        String knowledgeVersionFingerprint,
        String modelName,
        String promptVersion) {

    public RagAnswerCacheContext {
        tenantId = tenantId == null || tenantId.isBlank() ? "0" : tenantId.trim();
        knowledgeBaseIds = knowledgeBaseIds == null
                ? List.of()
                : knowledgeBaseIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .sorted()
                .distinct()
                .toList();
        knowledgeVersionFingerprint = knowledgeVersionFingerprint == null
                ? ""
                : knowledgeVersionFingerprint;
        modelName = modelName == null ? "" : modelName;
        promptVersion = promptVersion == null ? "" : promptVersion;
    }
}
