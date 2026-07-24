package org.jeecg.modules.airag.practice.eval.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalResult;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * AI评测报告响应 VO。
 * 用于汇总展示一次评测运行（runId）的整体胜率、得分大盘与各项明细。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI评测报告响应")
public class AiEvalReportVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评测运行唯一ID，全局UUID */
    @Schema(description = "评测运行ID")
    private String runId;

    /** 评测运行名称，方便页面顶部标题展示 */
    @Schema(description = "评测运行名称")
    private String runName;

    /** 本次运行评测的总用例数量 */
    @Schema(description = "总用例数")
    private Integer totalCases;

    /** 本次运行通过考核门槛（综合得分>=70）的用例数量 */
    @Schema(description = "通过用例数")
    private Integer passedCases;

    /** 用例总体通过率，百分比 (0.00 ~ 100.00) */
    @Schema(description = "通过率，0-100")
    private BigDecimal passRate;

    /** 所有用例加权计算后的平均综合得分 (0.00 ~ 100.00) */
    @Schema(description = "平均综合得分，0-100")
    private BigDecimal avgScore;

    /** RAG用例：回答相关性指标加权平均分 (针对 expected_keywords 匹配度) */
    @Schema(description = "RAG回答相关性平均分")
    private BigDecimal ragAnswerRelevance;

    /** RAG用例：知识库引用命中加权平均分 (针对 expected_references 匹配度) */
    @Schema(description = "RAG引用命中平均分")
    private BigDecimal ragReferenceHit;

    /** RAG用例：防幻觉拒答加权平均分 (针对知识库无答案/越权时是否正确拒答) */
    @Schema(description = "RAG拒答平均分")
    private BigDecimal ragReject;

    /** Agent用例：工具选择正确率加权平均分 (针对模型是否准确选对 target tool) */
    @Schema(description = "Agent工具选择平均分")
    private BigDecimal agentToolSelection;

    /** Agent用例：工具参数准确度加权平均分 (针对模型生成 JSON 参数与预期的吻合度) */
    @Schema(description = "Agent参数准确平均分")
    private BigDecimal agentParamAccuracy;

    /** Agent用例：任务完成度加权平均分 (针对工具执行后最终回复与预期结果的比对) */
    @Schema(description = "Agent任务完成平均分")
    private BigDecimal agentTaskCompletion;

    /** 逐条用例的详细执行与评分结果列表 */
    @Schema(description = "逐用例评测结果明细列表")
    private List<AiEvalResult> results;
}

