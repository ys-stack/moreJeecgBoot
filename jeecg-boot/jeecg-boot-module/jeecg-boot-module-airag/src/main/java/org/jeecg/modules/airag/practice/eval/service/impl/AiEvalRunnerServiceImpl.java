package org.jeecg.modules.airag.practice.eval.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.airag.practice.chat.service.RagChatService;
import org.jeecg.modules.airag.practice.chat.vo.RagChatRequest;
import org.jeecg.modules.airag.practice.chat.vo.RagChatResponse;
import org.jeecg.modules.airag.practice.doc.service.impl.BatchParseServiceImpl;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalDataset;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalResult;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalDatasetService;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalResultService;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalRunnerService;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalReportVO;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalRunRequest;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalRunTask;
import org.jeecg.modules.airag.practice.threadpool.PracticeThreadPool;
import org.jeecg.modules.airag.practice.tool.controller.ToolChatController;
import org.jeecg.modules.airag.practice.tool.service.ToolChatService;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AiEvalRunnerServiceImpl implements IAiEvalRunnerService {
    @Resource
    private IAiEvalDatasetService datasetService;
    @Resource
    private IAiEvalResultService resultService;
    @Resource
    private RagChatService ragChatService;
    @Resource
    private ToolChatService toolChatService;
    @Resource
    @Qualifier("practiceAsyncPool")
    private PracticeThreadPool asyncPool;
    @Resource
    private RedisTemplate redisTemplate;

    private static final String CACHE_EVAL_RUNNER_PREFIX = "eval:runner:";

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:14
     * @Desc: 一键执行评测：加载用例 -> 执行RAG/Agent -> 评分 -> 落库 -> 返回报告
     */
    @Override
    public AiEvalReportVO run(AiEvalRunRequest request, String userId) {
        if (request == null) {
            request = new AiEvalRunRequest();
        }
        String runId = UUID.randomUUID().toString().replace("-", "");
        //按评测类型和用例编码筛选本次要跑的用例
        List<AiEvalDataset> cases = loadCases(request);
        if (ObjectUtils.isEmpty(cases)) {
            throw new RuntimeException("测试用例不能为空!");
        }
        for (int i = 0; i < cases.size(); i++) {
            AiEvalDataset evalDataset = cases.get(i);
            AiEvalRunRequest finalRequest = request;
            asyncPool.submit(() -> {
                AiEvalResult result = "agent".equals(evalDataset.getEvalType())
                        ? runAgentCase(runId, finalRequest, evalDataset, userId)
                        : runRagCase(runId, finalRequest, evalDataset, userId);
                resultService.save(result);
            });
        }
        return report(runId);
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:21
     * @Desc: 构建异常返回
     */
    private AiEvalResult buildErrorResult(String runId, AiEvalRunRequest request, AiEvalDataset aiEvalDataset, String userId, Exception e) {
        return baseResult(runId, request, aiEvalDataset, userId)
                .setTotalScore(BigDecimal.ZERO)
                .setPassed(0)
                .setStatus("error")
                .setErrorMsg(StringUtils.left(e.getMessage(), 1000))
                .setCreateTime(new Date());
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:18
     * @Desc: 按评测类型和用例编码筛选本次要跑的用例
     */
    private List<AiEvalDataset> loadCases(AiEvalRunRequest request) {
        List<AiEvalDataset> list = datasetService.listEnabled(request.getEvalType());
        if (ObjectUtils.isEmpty(request.getCaseCodes())) {
            return list;
        }
        return list.stream().filter(item -> request.getCaseCodes().contains(item.getCaseCode())).collect(Collectors.toList());
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:18
     * @Desc: 执行单条Agent评测用例，并计算工具选择、参数准确、任务完成
     */
    private AiEvalResult runAgentCase(String runId, AiEvalRunRequest request, AiEvalDataset aiEvalDataset, String userId) {
        long start = System.currentTimeMillis();

        ToolChatController.ToolChatRequest chatReq = new ToolChatController.ToolChatRequest();
        chatReq.setMessage(aiEvalDataset.getQuestion());
        chatReq.setConfirmTools(StringUtils.isNotBlank(aiEvalDataset.getExpectedToolName())
                ? List.of(aiEvalDataset.getExpectedToolName())
                : Collections.emptyList());

        ToolChatResponse resp = toolChatService.chatWithTools(chatReq);
        long cost = System.currentTimeMillis() - start;

        BigDecimal toolSelection = scoreTool(resp.getToolCalls(), aiEvalDataset.getExpectedToolName());
        BigDecimal paramAccuracy = scoreParams(resp.getToolCalls(), aiEvalDataset.getExpectedToolName(), aiEvalDataset.getExpectedToolParams());
        BigDecimal taskCompletion = scoreTask(resp, aiEvalDataset.getExpectedTaskResult());
        BigDecimal total = toolSelection.multiply(new BigDecimal("0.4"))
                .add(paramAccuracy.multiply(new BigDecimal("0.3")))
                .add(taskCompletion.multiply(new BigDecimal("0.3")))
                .setScale(2, RoundingMode.HALF_UP);
        boolean passed = total.compareTo(new BigDecimal("70")) >= 0;

        Map<String, Object> judgeDetail = new LinkedHashMap<>();
        judgeDetail.put("toolSelection", toolSelection);
        judgeDetail.put("paramAccuracy", paramAccuracy);
        judgeDetail.put("taskCompletion", taskCompletion);
        judgeDetail.put("expectedToolName", aiEvalDataset.getExpectedToolName());
        judgeDetail.put("expectedToolParams", aiEvalDataset.getExpectedToolParams());
        judgeDetail.put("expectedTaskResult", aiEvalDataset.getExpectedTaskResult());

        return baseResult(runId, request, aiEvalDataset, userId)
                .setActualAnswer(resp.getContent())
                .setActualToolCalls(JSON.toJSONString(resp.getToolCalls()))
                .setRawResponse(JSON.toJSONString(resp))
                .setToolSelectionScore(toolSelection)
                .setParamAccuracyScore(paramAccuracy)
                .setTaskCompletionScore(taskCompletion)
                .setTotalScore(total)
                .setPassed(passed ? 1 : 0)
                .setDurationMs(cost)
                .setModelName(StringUtils.defaultIfBlank(request.getModelName(), resp.getModel()))
                .setStatus(passed ? "success" : "fail")
                .setJudgeDetail(JSON.toJSONString(judgeDetail))
                .setCreateTime(new Date());
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:18
     * @Desc: 执行单条RAG评测用例，并计算回答相关性、引用命中率、拒答率
     */
    private AiEvalResult runRagCase(String runId, AiEvalRunRequest request, AiEvalDataset aiEvalDataset, String userId) {
        long start = System.currentTimeMillis();

        RagChatRequest chatReq = new RagChatRequest();
        chatReq.setQuery(aiEvalDataset.getQuestion());
        chatReq.setKnowledgeBaseId(aiEvalDataset.getKnowledgeBaseId());
        chatReq.setTopK(5);

        RagChatResponse resp = ragChatService.ragChat(chatReq, userId);
        long cost = System.currentTimeMillis() - start;


        BigDecimal relevance = scoreKeywords(resp.getAnswer(), aiEvalDataset.getExpectedKeywords());
        BigDecimal refHit = scoreReferences(resp.getReferences(), aiEvalDataset.getExpectedReferences());
        BigDecimal reject = scoreReject(resp.getAnswer(), aiEvalDataset.getExpectedReject());
        BigDecimal total = aiEvalDataset.getExpectedReject() != null && aiEvalDataset.getExpectedReject() == 1
                ? reject
                : relevance.multiply(new BigDecimal("0.5"))
                .add(refHit.multiply(new BigDecimal("0.3")))
                .add(reject.multiply(new BigDecimal("0.2")));
        total = total.setScale(2, RoundingMode.HALF_UP);
        boolean passed = total.compareTo(new BigDecimal("70")) >= 0;

        Map<String, Object> judgeDetail = new LinkedHashMap<>();
        judgeDetail.put("answerRelevance", relevance);
        judgeDetail.put("referenceHit", refHit);
        judgeDetail.put("reject", reject);
        judgeDetail.put("expectedKeywords", aiEvalDataset.getExpectedKeywords());
        judgeDetail.put("expectedReferences", aiEvalDataset.getExpectedReferences());
        judgeDetail.put("expectedReject", aiEvalDataset.getExpectedReject());

        return baseResult(runId, request, aiEvalDataset, userId)
                .setActualAnswer(resp.getAnswer())
                .setActualReferences(JSON.toJSONString(resp.getReferences()))
                .setRawResponse(JSON.toJSONString(resp))
                .setAnswerRelevanceScore(relevance)
                .setReferenceHitScore(refHit)
                .setRejectScore(reject)
                .setTotalScore(total)
                .setPassed(passed ? 1 : 0)
                .setDurationMs(cost)
                .setPromptTokens(resp.getPromptTokens())
                .setCompletionTokens(resp.getCompletionTokens())
                .setTotalTokens(sum(resp.getPromptTokens(), resp.getCompletionTokens()))
                .setModelName(StringUtils.defaultIfBlank(request.getModelName(), resp.getModel()))
                .setStatus(passed ? "success" : "fail")
                .setJudgeDetail(JSON.toJSONString(judgeDetail))
                .setCreateTime(new Date());
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:33
     * @Desc:
     */
    private AiEvalResult baseResult(String runId, AiEvalRunRequest request, AiEvalDataset aiEvalDataset, String userId) {
        String runName = StringUtils.defaultIfBlank(request.getRunName(), "AI评测-" + runId.substring(0, 8));
        return new AiEvalResult()
                .setRunId(runId)
                .setRunName(runName)
                .setDatasetId(aiEvalDataset.getId())
                .setCaseCode(aiEvalDataset.getCaseCode())
                .setEvalType(aiEvalDataset.getEvalType())
                .setPromptCode(request.getPromptCode())
                .setPromptVersion(request.getPromptVersion())
                .setModelProvider(request.getModelProvider())
                .setModelName(request.getModelName())
                .setQuestion(aiEvalDataset.getQuestion())
                .setCreateBy(userId)
                .setCreateTime(new Date());
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:33
     * @Desc: 判断该拒答时是否拒答，不该拒答时是否正常回答
     */
    private BigDecimal scoreReject(String answer, Integer expectedReject) {
        answer = StringUtils.defaultString(answer);
        boolean rejected = StringUtils.containsAny(answer, "未找到", "无法回答", "没有相关信息", "知识库中未找到");
        if (expectedReject != null && expectedReject == 1) {
            return rejected ? new BigDecimal("100") : BigDecimal.ZERO;
        }
        return rejected ? BigDecimal.ZERO : new BigDecimal("100");
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:34
     * @Desc: 根据预期引用和实际引用计算引用命中率
     */
    private BigDecimal scoreReferences(List<RagChatResponse.ReferenceChunk> references, String expectedRefsJson) {
        if (StringUtils.isBlank(expectedRefsJson)) return new BigDecimal("100");
        List<String> expected = parseStringArray(expectedRefsJson);
        if (expected == null || expected.isEmpty()) return new BigDecimal("100");

        String actual = JSON.toJSONString(references);
        long hit = expected.stream().filter(x -> actual != null && actual.contains(x)).count();
        return BigDecimal.valueOf(hit * 100.0 / expected.size()).setScale(2, RoundingMode.HALF_UP);
    }

    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:31
     * @Desc: 根据关键词命中比例（支持同义词组二维数组）计算回答相关性
     */
    private BigDecimal scoreKeywords(String answer, String expectedKeywordsJson) {
        if (StringUtils.isBlank(expectedKeywordsJson) || StringUtils.isBlank(answer)) {
            return new BigDecimal("100");
        }

        try {
            // 优先尝试解析为二维同义词组数组：[["RDB", "快照"], ["AOF", "日志"]]
            List<List<String>> synonymGroups = JSON.parseObject(expectedKeywordsJson, new TypeReference<List<List<String>>>() {});
            if (synonymGroups != null && !synonymGroups.isEmpty()) {
                long hitGroupCount = 0;
                for (List<String> group : synonymGroups) {
                    boolean groupHit = group.stream()
                            .anyMatch(kw -> StringUtils.isNotBlank(kw) && StringUtils.containsIgnoreCase(answer, kw));
                    if (groupHit) {
                        hitGroupCount++;
                    }
                }
                return BigDecimal.valueOf(hitGroupCount * 100.0 / synonymGroups.size()).setScale(2, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            // 降级为一维简单关键词列表
        }

        List<String> keywords = parseStringArray(expectedKeywordsJson);
        if (keywords == null || keywords.isEmpty()) return new BigDecimal("100");

        long hit = keywords.stream()
                .filter(k -> StringUtils.isNotBlank(k) && StringUtils.containsIgnoreCase(answer, k))
                .count();

        return BigDecimal.valueOf(hit * 100.0 / keywords.size()).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 根据工具调用列表计算工具选择正确率。
     */
    private BigDecimal scoreTool(List<ToolChatResponse.ToolCallDetail> toolCalls, String expectedToolName) {
        if (StringUtils.isBlank(expectedToolName)) {
            return new BigDecimal("100");
        }
        if (ObjectUtils.isEmpty(toolCalls)) {
            return BigDecimal.ZERO;
        }
        boolean hit = toolCalls.stream().anyMatch(item -> expectedToolName.equals(item.getToolCode()));
        return hit ? new BigDecimal("100") : BigDecimal.ZERO;
    }

    /**
     * 根据预期参数和实际入参计算参数准确率。
     */
    private BigDecimal scoreParams(List<ToolChatResponse.ToolCallDetail> toolCalls, String expectedToolName, String expectedParamsJson) {
        if (StringUtils.isBlank(expectedParamsJson)) {
            return new BigDecimal("100");
        }
        ToolChatResponse.ToolCallDetail detail = findToolCall(toolCalls, expectedToolName);
        if (detail == null || StringUtils.isBlank(detail.getInputParams())) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> expected = parseJsonObject(expectedParamsJson);
        Map<String, Object> actual = parseJsonObject(detail.getInputParams());
        if (expected.isEmpty()) {
            return new BigDecimal("100");
        }
        long hit = expected.entrySet().stream()
                .filter(entry -> Objects.equals(String.valueOf(entry.getValue()), String.valueOf(actual.get(entry.getKey()))))
                .count();
        return BigDecimal.valueOf(hit * 100.0 / expected.size()).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 根据最终回答和工具输出计算任务完成率。
     */
    private BigDecimal scoreTask(ToolChatResponse resp, String expectedTaskResult) {
        if (StringUtils.isBlank(expectedTaskResult)) {
            return new BigDecimal("100");
        }
        StringBuilder actual = new StringBuilder(StringUtils.defaultString(resp.getContent()));
        if (resp.getToolCalls() != null) {
            resp.getToolCalls().forEach(item -> actual.append('\n').append(StringUtils.defaultString(item.getOutputResult())));
        }
        if (expectedTaskResult.trim().startsWith("[")) {
            return scoreKeywords(actual.toString(), expectedTaskResult);
        }
        return StringUtils.containsIgnoreCase(actual.toString(), expectedTaskResult) ? new BigDecimal("100") : BigDecimal.ZERO;
    }


    /*
     * @Author: ys
     * @Date: 2026/7/5 星期日 22:20
     * @Desc: 根据 runId 汇总评测报告（结合用例权重加权计算平均分）
     */
    @Override
    public AiEvalReportVO report(String runId) {
        List<AiEvalResult> results = resultService.listByRunId(runId);
        int totalCases = results == null ? 0 : results.size();
        if (totalCases == 0) {
            return AiEvalReportVO.builder().runId(runId).totalCases(0).passRate(BigDecimal.ZERO).build();
        }

        int passedCases = (int) results.stream()
                .filter(item -> item.getPassed() != null && item.getPassed() == 1)
                .count();

        Map<String, BigDecimal> caseWeightMap = datasetService.list().stream()
                .collect(Collectors.toMap(AiEvalDataset::getId, item -> item.getWeight() != null ? item.getWeight() : BigDecimal.ONE, (k1, k2) -> k1));

        return AiEvalReportVO.builder()
                .runId(runId)
                .runName(results.get(0).getRunName())
                .totalCases(totalCases)
                .passedCases(passedCases)
                .passRate(BigDecimal.valueOf(passedCases * 100.0 / totalCases).setScale(2, RoundingMode.HALF_UP))
                .avgScore(calculateWeightedAvg(results, AiEvalResult::getTotalScore, caseWeightMap))
                .ragAnswerRelevance(calculateWeightedAvg(results, AiEvalResult::getAnswerRelevanceScore, caseWeightMap))
                .ragReferenceHit(calculateWeightedAvg(results, AiEvalResult::getReferenceHitScore, caseWeightMap))
                .ragReject(calculateWeightedAvg(results, AiEvalResult::getRejectScore, caseWeightMap))
                .agentToolSelection(calculateWeightedAvg(results, AiEvalResult::getToolSelectionScore, caseWeightMap))
                .agentParamAccuracy(calculateWeightedAvg(results, AiEvalResult::getParamAccuracyScore, caseWeightMap))
                .agentTaskCompletion(calculateWeightedAvg(results, AiEvalResult::getTaskCompletionScore, caseWeightMap))
                .results(results)
                .build();
    }

    /**
     * 通用加权平均分计算函数
     */
    private BigDecimal calculateWeightedAvg(List<AiEvalResult> results,
                                           Function<AiEvalResult, BigDecimal> scoreGetter,
                                           Map<String, BigDecimal> caseWeightMap) {
        if (ObjectUtils.isEmpty(results)) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalWeightedScore = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;

        for (AiEvalResult result : results) {
            BigDecimal score = scoreGetter.apply(result);
            if (score == null) continue;

            BigDecimal weight = caseWeightMap.getOrDefault(result.getDatasetId(), BigDecimal.ONE);
            totalWeightedScore = totalWeightedScore.add(score.multiply(weight));
            totalWeight = totalWeight.add(weight);
        }

        if (totalWeight.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return totalWeightedScore.divide(totalWeight, 2, RoundingMode.HALF_UP);
    }

    /*
     * @Author: ys
     * @Date: 2026/7/23 星期四 22:10
     * @Desc: 提交异步任务
     */
    @Override
    public AiEvalRunTask submitRunAsync(AiEvalRunRequest request, String userId) {
        if (request == null) request = new AiEvalRunRequest();
        String runId = UUID.randomUUID().toString().replace("-", "");
        String runName = StringUtils.defaultIfBlank(request.getRunName(), "AI评测-" + runId.substring(0, 8));

        List<AiEvalDataset> cases = loadCases(request);
        AiEvalRunTask task = new AiEvalRunTask()
                .setRunId(runId)
                .setRunName(runName)
                .setStatus("RUNNING")
                .setTotalCases(cases.size())
                .setProcessedCases(0)
                .setPassedCases(0)
                .setStartTime(new Date());

        redisTemplate.opsForValue().set(CACHE_EVAL_RUNNER_PREFIX + runId, task, 24, TimeUnit.HOURS);
        // 异步提交到线程池中运行，避免 HTTP 主线程阻塞超时
        final AiEvalRunRequest reqCopy = request;
        asyncPool.submit(() -> executeTaskAsync(runId, reqCopy, cases, userId));
        return task;
    }

    /*
     * @Author: ys
     * @Date: 2026/7/23 星期四 22:42
     * @Desc: 异步执行任务逻辑
     */
    private void executeTaskAsync(String runId, AiEvalRunRequest request, List<AiEvalDataset> cases, String userId) {
        AiEvalRunTask task = (AiEvalRunTask) redisTemplate.opsForValue().get(CACHE_EVAL_RUNNER_PREFIX + runId);
        if (task == null) {
            task = new AiEvalRunTask()
                    .setRunId(runId)
                    .setStatus("RUNNING")
                    .setTotalCases(cases.size())
                    .setProcessedCases(0)
                    .setPassedCases(0)
                    .setStartTime(new Date());
        }
        int passedCount = 0;

        try {
            for (AiEvalDataset item : cases) {
                // 更新当前正在处理的用例编码，方便前端展示“正在评测 RAG_005...”
                task.setCurrentCaseCode(item.getCaseCode());
                AiEvalResult result;
                try {
                    result = "agent".equals(item.getEvalType())
                            ? runAgentCase(runId, request, item, userId)
                            : runRagCase(runId, request, item, userId);
                } catch (Exception e) {
                    log.error("评测用例异步执行失败: {}", item.getCaseCode(), e);
                    result = buildErrorResult(runId, request, item, userId, e);
                }
                resultService.save(result);
                if (result.getPassed() != null && result.getPassed() == 1) {
                    passedCount++;
                }
                // 递增已处理计数并同步写回 Redis
                task.setProcessedCases(task.getProcessedCases() + 1);
                task.setPassedCases(passedCount);
                redisTemplate.opsForValue().set(CACHE_EVAL_RUNNER_PREFIX + runId, task, 24, TimeUnit.HOURS);
            }
            task.setStatus("COMPLETED")
                    .setCurrentCaseCode("DONE")
                    .setEndTime(new Date());
            redisTemplate.opsForValue().set(CACHE_EVAL_RUNNER_PREFIX + runId, task, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("评测任务运行致命异常, runId={}", runId, e);
            task.setStatus("FAILED")
                    .setErrorMsg(e.getMessage())
                    .setEndTime(new Date());
            redisTemplate.opsForValue().set(CACHE_EVAL_RUNNER_PREFIX + runId, task, 24, TimeUnit.HOURS);
        }
    }

    /*
     * @Author: ys
     * @Date: 2026/7/23 星期四 22:11
     * @Desc: 查看任务进度
     */
    @Override
    public AiEvalRunTask getTaskStatus(String runId) {
        return (AiEvalRunTask) redisTemplate.opsForValue().get(CACHE_EVAL_RUNNER_PREFIX+runId);
    }

    /**
     * 汇总两个报告之间的指标差值。
     */
    private Map<String, BigDecimal> buildDeltas(AiEvalReportVO base, AiEvalReportVO target) {
        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        deltas.put("passRateDelta", subtract(target.getPassRate(), base.getPassRate()));
        deltas.put("avgScoreDelta", subtract(target.getAvgScore(), base.getAvgScore()));
        deltas.put("ragAnswerRelevanceDelta", subtract(target.getRagAnswerRelevance(), base.getRagAnswerRelevance()));
        deltas.put("ragReferenceHitDelta", subtract(target.getRagReferenceHit(), base.getRagReferenceHit()));
        deltas.put("ragRejectDelta", subtract(target.getRagReject(), base.getRagReject()));
        deltas.put("agentToolSelectionDelta", subtract(target.getAgentToolSelection(), base.getAgentToolSelection()));
        deltas.put("agentParamAccuracyDelta", subtract(target.getAgentParamAccuracy(), base.getAgentParamAccuracy()));
        deltas.put("agentTaskCompletionDelta", subtract(target.getAgentTaskCompletion(), base.getAgentTaskCompletion()));
        return deltas;
    }

    /**
     * 计算某个评分字段的平均值。
     */
    private BigDecimal avg(List<AiEvalResult> results, Function<AiEvalResult, BigDecimal> getter) {
        if (ObjectUtils.isEmpty(results)) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> values = results.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * 查找指定工具的实际调用记录。
     */
    private ToolChatResponse.ToolCallDetail findToolCall(List<ToolChatResponse.ToolCallDetail> toolCalls, String expectedToolName) {
        if (ObjectUtils.isEmpty(toolCalls)) {
            return null;
        }
        if (StringUtils.isBlank(expectedToolName)) {
            return toolCalls.get(0);
        }
        return toolCalls.stream()
                .filter(item -> expectedToolName.equals(item.getToolCode()))
                .findFirst()
                .orElse(toolCalls.get(0));
    }

    /**
     * 安全解析JSON数组，格式异常时返回空列表。
     */
    private List<String> parseStringArray(String json) {
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            log.warn("评测JSON数组解析失败: {}", json, e);
            return Collections.emptyList();
        }
    }

    /**
     * 安全解析JSON对象，格式异常时返回空Map。
     */
    private Map<String, Object> parseJsonObject(String json) {
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            log.warn("评测JSON对象解析失败: {}", json, e);
            return new HashMap<>();
        }
    }

    /**
     * 汇总token数量，避免空值导致拆箱异常。
     */
    private Integer sum(Integer promptTokens, Integer completionTokens) {
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        return prompt + completion;
    }

    /**
     * BigDecimal安全相减。
     */
    private BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return defaultNumber(left).subtract(defaultNumber(right)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 空数字按0处理。
     */
    private BigDecimal defaultNumber(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
