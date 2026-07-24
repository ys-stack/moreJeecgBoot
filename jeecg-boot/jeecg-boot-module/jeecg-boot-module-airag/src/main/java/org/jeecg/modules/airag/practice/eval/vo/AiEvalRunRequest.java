package org.jeecg.modules.airag.practice.eval.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AI评测执行请求 VO。
 * 用户在前端界面点击“发起评测”时提交的参数载体。
 */
@Data
@Schema(description = "AI评测执行请求")
public class AiEvalRunRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本次评测运行自定义名称，为空时后端自动生成 */
    @Schema(description = "本次评测运行名称")
    private String runName;

    /** 评测类型筛选：rag(仅跑RAG用例) / agent(仅跑Agent用例)，传空表示跑全部 */
    @Schema(description = "评测类型：rag / agent，传空表示全部")
    private String evalType;

    /** 指定要运行的用例编码列表(如 [RAG_001, AGENT_002])，传空表示运行全部启用用例 */
    @Schema(description = "指定要运行的用例编码列表")
    private List<String> caseCodes;

    /** 本次评测绑定的 Prompt 模板编码，用于记录与对比 Prompt 效果 */
    @Schema(description = "本次评测使用的Prompt编码")
    private String promptCode;

    /** 本次评测绑定的 Prompt 模板版本号 */
    @Schema(description = "本次评测使用的Prompt版本号")
    private Integer promptVersion;

    /** 本次评测调用的模型供应商，如 deepseek、siliconflow、aliyun */
    @Schema(description = "模型供应商")
    private String modelProvider;

    /** 本次评测调用的具体模型名称，如 deepseek-chat、qwen2.5-72b */
    @Schema(description = "模型名称")
    private String modelName;
}

