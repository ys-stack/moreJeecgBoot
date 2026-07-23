package org.jeecg.modules.airag.practice.eval.service;

import org.jeecg.modules.airag.practice.eval.vo.AiEvalReportVO;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalRunRequest;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalRunTask;

import java.util.Map;

public interface IAiEvalRunnerService {
    /** 一键执行评测，并返回本次评测报告 */
    AiEvalReportVO run(AiEvalRunRequest request, String userId);

    /** 根据 runId 汇总评测报告 */
    AiEvalReportVO report(String runId);

    /** 对比两次评测运行的指标差异 */
    Map<String, Object> compare(String baseRunId, String targetRunId);

    // 提交异步任务
    AiEvalRunTask submitRunAsync(AiEvalRunRequest request, String userId);

    // 查询当前任务进度
    AiEvalRunTask getTaskStatus(String runId);
}
