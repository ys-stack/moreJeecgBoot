package org.jeecg.modules.airag.practice.eval.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Date;

/**
 * AI评测任务实时运行状态 VO。
 * 用于异步评测时在 Redis / 内存中保存和轮询当前运行进度。
 */
@Data
@Accessors(chain = true)
@Schema(description = "AI评测任务实时运行状态")
public class AiEvalRunTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评测运行ID，一次批量评测对应唯一的UUID编码 */
    @Schema(description = "评测运行ID")
    private String runId;

    /** 本次评测任务名称，例如：AI评测-20260724 */
    @Schema(description = "评测任务名称")
    private String runName;

    /** 任务状态：RUNNING(运行中) / COMPLETED(已完成) / FAILED(失败) */
    @Schema(description = "任务状态：RUNNING / COMPLETED / FAILED")
    private String status;

    /** 本次任务需要执行的总用例数 */
    @Schema(description = "总用例数")
    private Integer totalCases = 0;

    /** 当前已经处理（跑完）的用例数量 */
    @Schema(description = "已处理用例数")
    private Integer processedCases = 0;

    /** 当前已经通过评测（达到分数门槛）的用例数量 */
    @Schema(description = "通过用例数")
    private Integer passedCases = 0;

    /** 当前正在跑的用例编码，例如：RAG_005，方便前端实时提示进度 */
    @Schema(description = "当前正在执行的用例编码")
    private String currentCaseCode;

    /** 任务失败时的错误日志信息 */
    @Schema(description = "失败错误信息")
    private String errorMsg;

    /** 评测任务开始启动时间 */
    @Schema(description = "任务开始时间")
    private Date startTime;

    /** 评测任务结束时间 */
    @Schema(description = "任务结束时间")
    private Date endTime;

    /**
     * 计算当前评测任务的百分比进度 (0.00 ~ 100.00)。
     *
     * @return 进度百分比数值
     */
    public double getProgressPercentage() {
        if (totalCases == null || totalCases == 0) return 0.0;
        return Math.round((processedCases * 100.0 / totalCases) * 100.0) / 100.0;
    }
}