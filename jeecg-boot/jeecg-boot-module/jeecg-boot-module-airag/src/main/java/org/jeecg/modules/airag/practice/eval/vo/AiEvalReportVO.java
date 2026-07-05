package org.jeecg.modules.airag.practice.eval.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI评测报告响应。
 * 用于汇总展示一次评测运行的整体指标和明细。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiEvalReportVO {

    /** 评测运行ID */
    private String runId;

    /** 评测运行名称 */
    private String runName;

    /** 总用例数 */
    private Integer totalCases;

    /** 通过用例数 */
    private Integer passedCases;

    /** 通过率，0-100 */
    private BigDecimal passRate;

    /** 平均综合得分，0-100 */
    private BigDecimal avgScore;

    /** RAG回答相关性平均分 */
    private BigDecimal ragAnswerRelevance;

    /** RAG引用命中平均分 */
    private BigDecimal ragReferenceHit;

    /** RAG拒答平均分 */
    private BigDecimal ragReject;

    /** Agent工具选择平均分 */
    private BigDecimal agentToolSelection;

    /** Agent参数准确平均分 */
    private BigDecimal agentParamAccuracy;

    /** Agent任务完成平均分 */
    private BigDecimal agentTaskCompletion;

    /** 逐用例评测结果明细 */
    private List<AiEvalResult> results;
}
