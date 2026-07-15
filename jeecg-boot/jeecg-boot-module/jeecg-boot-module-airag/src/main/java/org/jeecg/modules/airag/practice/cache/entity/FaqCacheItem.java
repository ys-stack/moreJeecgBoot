package org.jeecg.modules.airag.practice.cache.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jeecg.modules.airag.practice.chat.vo.RagChatResponse;

import java.io.Serializable;
import java.util.List;

/**
 * RAG FAQ 答案缓存对象。
 *
 * <p>不保存原始问题，避免用户敏感内容长期进入 Redis；问题只保存 HMAC 摘要。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaqCacheItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 完整缓存键的 HMAC 摘要。 */
    private String cacheKey;

    /** 数据所属租户，防止跨租户复用答案。 */
    private String tenantId;

    /** 归一化问题的 HMAC 摘要，不保存原始问题。 */
    private String questionHash;

    /** 生成该答案时使用的知识库 ID 集合。 */
    private List<String> knowledgeBaseIds;

    /** 知识库版本指纹，任意知识库内容变化后都会变化。 */
    private String knowledgeVersionFingerprint;

    /** 模型生成的最终答案。 */
    private String answer;

    /** 返回给前端的引用来源。 */
    private List<RagChatResponse.ReferenceChunk> references;

    /** 原始 RAG 检索结果 JSON，用于保存聊天审计记录。 */
    private String ragContextJson;

    /** 生成答案时使用的模型。 */
    private String modelName;

    /** 缓存创建时间戳，单位毫秒。 */
    private Long createTime;

    /** 缓存绝对过期时间戳，单位毫秒。 */
    private Long expireTime;
}
