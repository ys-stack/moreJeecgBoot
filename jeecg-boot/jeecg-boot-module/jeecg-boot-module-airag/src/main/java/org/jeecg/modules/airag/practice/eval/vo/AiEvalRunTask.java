package org.jeecg.modules.airag.practice.eval.vo;

import lombok.Data;
import lombok.experimental.Accessors;
import java.util.Date;

/**
 * 评测任务实时运行状态 VO（可存在 ConcurrentHashMap 或 Redis 中）
 */
@Data
@Accessors(chain = true)
public class AiEvalRunTask {
    private String runId;
    private String runName;
    private String status; // RUNNING / COMPLETED / FAILED
    private Integer totalCases = 0;
    private Integer processedCases = 0;
    private Integer passedCases = 0;
    private String currentCaseCode;
    private String errorMsg;
    private Date startTime;
    private Date endTime;

    public double getProgressPercentage() {
        if (totalCases == null || totalCases == 0) return 0.0;
        return Math.round((processedCases * 100.0 / totalCases) * 100.0) / 100.0;
    }
}