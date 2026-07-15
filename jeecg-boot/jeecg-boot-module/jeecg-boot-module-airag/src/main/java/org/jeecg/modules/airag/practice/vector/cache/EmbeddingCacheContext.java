package org.jeecg.modules.airag.practice.vector.cache;

import java.util.Objects;

/*
 * @Author: ys
 * @Date: 2026/7/15 16:46
 * @DESC: Embedding 缓存上下文
 *
 * 设计：
 * 同一文本 + 同一 Embedding 模型 + 同一预处理版本 = 同一向量
 * 会话、用户、知识库范围不影响向量本身。为了防止租户间通过哈希或缓存命中推断数据，缓存键仍然包含 tenantId。
 */
public record EmbeddingCacheContext(String tenantId) {

    public EmbeddingCacheContext {
        tenantId = normalizeTenantId(tenantId);
    }

    public static EmbeddingCacheContext tenant(String tenantId) {
        return new EmbeddingCacheContext(tenantId);
    }

    public static EmbeddingCacheContext defaultTenant() {
        return new EmbeddingCacheContext("0");
    }

    private static String normalizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "0";
        }
        return Objects.requireNonNull(tenantId).trim();
    }
}