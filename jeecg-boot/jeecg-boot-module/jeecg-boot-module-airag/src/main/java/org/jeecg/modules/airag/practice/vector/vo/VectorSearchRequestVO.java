package org.jeecg.modules.airag.practice.vector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 向量检索请求 VO
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Data
@Schema(description = "向量检索请求")
public class VectorSearchRequestVO {

    @Schema(description = "查询文本", example = "Redis 持久化方式有哪些")
    private String query;

    @Schema(description = "返回条数", defaultValue = "5")
    private int topK = 5;

    @Schema(description = "知识库ID（可选，为空则搜索全部）")
    private String knowledgeBaseId;
}
