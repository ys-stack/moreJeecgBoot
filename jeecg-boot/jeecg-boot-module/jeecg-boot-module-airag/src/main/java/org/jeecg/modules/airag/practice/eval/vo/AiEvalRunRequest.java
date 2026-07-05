package org.jeecg.modules.airag.practice.eval.vo;

import lombok.Data;

import java.util.List;

/**
 * AI评测执行请求。
 * 用于前端触发一次评测运行。
 */
@Data
public class AiEvalRunRequest {

    /** 本次评测运行名称 */
    private String runName;

    /** 评测类型：rag / agent，传空表示全部 */
    private String evalType;

    /** 指定要运行的用例编码列表，传空表示运行全部启用用例 */
    private List<String> caseCodes;

    /** 本次评测使用的Prompt编码 */
    private String promptCode;

    /** 本次评测使用的Prompt版本 */
    private Integer promptVersion;

    /** 本次评测使用的模型供应商 */
    private String modelProvider;

    /** 本次评测使用的模型名称 */
    private String modelName;
}
