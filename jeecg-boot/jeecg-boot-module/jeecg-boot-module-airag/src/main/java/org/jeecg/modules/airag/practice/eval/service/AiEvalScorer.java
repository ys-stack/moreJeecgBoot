package org.jeecg.modules.airag.practice.eval.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.airag.practice.chat.vo.RagChatResponse;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 评测规则评分器。黄金标注格式错误时直接失败，避免脏数据被按满分处理。
 */
public final class AiEvalScorer {

    private static final BigDecimal FULL_SCORE = new BigDecimal("100");

    private AiEvalScorer() {
    }

    public static BigDecimal scoreReject(String answer, Integer expectedReject) {
        String actual = StringUtils.defaultString(answer);
        boolean rejected = StringUtils.containsAny(actual,
                "未找到", "无法回答", "无法提供", "不能回答", "不能提供",
                "没有相关信息", "知识库中未找到", "超出知识库范围", "无权");
        boolean shouldReject = Objects.equals(expectedReject, 1);
        return rejected == shouldReject ? FULL_SCORE : BigDecimal.ZERO;
    }

    public static BigDecimal scoreReferences(List<RagChatResponse.ReferenceChunk> references,
                                             String expectedRefsJson) {
        List<String> expected = parseStringArray(expectedRefsJson, "expectedReferences");
        if (expected.isEmpty()) {
            return FULL_SCORE;
        }
        String actual = JSON.toJSONString(references == null ? Collections.emptyList() : references);
        long hit = expected.stream()
                .filter(StringUtils::isNotBlank)
                .filter(actual::contains)
                .count();
        return percentage(hit, expected.size());
    }

    public static BigDecimal scoreKeywords(String answer, String expectedKeywordsJson) {
        if (StringUtils.isBlank(expectedKeywordsJson)) {
            return FULL_SCORE;
        }
        if (StringUtils.isBlank(answer)) {
            return BigDecimal.ZERO;
        }

        Object parsed = parseJson(expectedKeywordsJson, "expectedKeywords");
        if (!(parsed instanceof List<?> raw) || raw.isEmpty()) {
            return FULL_SCORE;
        }

        if (raw.get(0) instanceof List<?>) {
            List<List<String>> groups;
            try {
                groups = JSON.parseObject(expectedKeywordsJson, new TypeReference<>() {
                });
            } catch (Exception e) {
                throw invalidGoldData("expectedKeywords", e);
            }
            long hit = groups.stream()
                    .filter(group -> group != null && !group.isEmpty())
                    .filter(group -> group.stream().anyMatch(keyword ->
                            StringUtils.isNotBlank(keyword) && StringUtils.containsIgnoreCase(answer, keyword)))
                    .count();
            return percentage(hit, groups.size());
        }

        List<String> keywords = parseStringArray(expectedKeywordsJson, "expectedKeywords");
        long hit = keywords.stream()
                .filter(StringUtils::isNotBlank)
                .filter(keyword -> StringUtils.containsIgnoreCase(answer, keyword))
                .count();
        return percentage(hit, keywords.size());
    }

    public static BigDecimal scoreTool(List<ToolChatResponse.ToolCallDetail> toolCalls,
                                       String expectedToolNames) {
        List<String> expected = parseExpectedTools(expectedToolNames);
        boolean noActualCalls = toolCalls == null || toolCalls.isEmpty();
        if (expected.isEmpty()) {
            return noActualCalls ? FULL_SCORE : BigDecimal.ZERO;
        }
        if (noActualCalls) {
            return BigDecimal.ZERO;
        }
        long hit = expected.stream()
                .filter(tool -> toolCalls.stream().anyMatch(call -> tool.equals(call.getToolCode())))
                .count();
        return percentage(hit, expected.size());
    }

    public static BigDecimal scoreParams(List<ToolChatResponse.ToolCallDetail> toolCalls,
                                         String expectedToolNames,
                                         String expectedParamsJson) {
        if (StringUtils.isBlank(expectedParamsJson)) {
            return FULL_SCORE;
        }
        List<String> expectedTools = parseExpectedTools(expectedToolNames);
        if (expectedTools.isEmpty()) {
            throw new IllegalArgumentException("expectedToolParams 非空时 expectedToolName 不能为空");
        }
        Map<String, Object> expected = parseJsonObject(expectedParamsJson, "expectedToolParams");
        if (expected.isEmpty()) {
            return FULL_SCORE;
        }
        ToolChatResponse.ToolCallDetail detail = findToolCall(toolCalls, expectedTools.get(0));
        if (detail == null || StringUtils.isBlank(detail.getInputParams())) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> actual = parseJsonObject(detail.getInputParams(), "actualToolParams");
        long hit = expected.entrySet().stream()
                .filter(entry -> Objects.equals(normalize(entry.getValue()), normalize(actual.get(entry.getKey()))))
                .count();
        return percentage(hit, expected.size());
    }

    public static BigDecimal scoreConfirmation(ToolChatResponse response, Integer shouldRequireConfirm) {
        boolean expected = Objects.equals(shouldRequireConfirm, 1);
        return response != null && response.isNeedsConfirm() == expected ? FULL_SCORE : BigDecimal.ZERO;
    }

    public static BigDecimal scoreTask(ToolChatResponse response, String expectedTaskResult) {
        if (StringUtils.isBlank(expectedTaskResult)) {
            return FULL_SCORE;
        }
        StringBuilder actual = new StringBuilder(response == null ? "" : StringUtils.defaultString(response.getContent()));
        if (response != null && response.getToolCalls() != null) {
            response.getToolCalls().forEach(call -> actual.append('\n')
                    .append(StringUtils.defaultString(call.getOutputResult())));
        }
        if (expectedTaskResult.trim().startsWith("[")) {
            return scoreKeywords(actual.toString(), expectedTaskResult);
        }
        return StringUtils.containsIgnoreCase(actual, expectedTaskResult) ? FULL_SCORE : BigDecimal.ZERO;
    }

    private static ToolChatResponse.ToolCallDetail findToolCall(List<ToolChatResponse.ToolCallDetail> calls,
                                                                 String expectedTool) {
        if (calls == null) {
            return null;
        }
        return calls.stream()
                .filter(call -> expectedTool.equals(call.getToolCode()))
                .findFirst()
                .orElse(null);
    }

    private static List<String> parseExpectedTools(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private static List<String> parseStringArray(String json, String fieldName) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            List<String> result = JSON.parseArray(json, String.class);
            return result == null ? Collections.emptyList() : result;
        } catch (Exception e) {
            throw invalidGoldData(fieldName, e);
        }
    }

    private static Map<String, Object> parseJsonObject(String json, String fieldName) {
        try {
            Map<String, Object> result = JSON.parseObject(json);
            return result == null ? Collections.emptyMap() : result;
        } catch (Exception e) {
            throw invalidGoldData(fieldName, e);
        }
    }

    private static Object parseJson(String json, String fieldName) {
        try {
            return JSON.parse(json);
        } catch (Exception e) {
            throw invalidGoldData(fieldName, e);
        }
    }

    private static IllegalArgumentException invalidGoldData(String fieldName, Exception cause) {
        return new IllegalArgumentException(fieldName + " 不是合法 JSON，评测已按错误处理", cause);
    }

    private static String normalize(Object value) {
        return value == null ? null : StringUtils.trim(String.valueOf(value));
    }

    private static BigDecimal percentage(long hit, int total) {
        if (total == 0) {
            return FULL_SCORE;
        }
        return BigDecimal.valueOf(hit * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }
}
