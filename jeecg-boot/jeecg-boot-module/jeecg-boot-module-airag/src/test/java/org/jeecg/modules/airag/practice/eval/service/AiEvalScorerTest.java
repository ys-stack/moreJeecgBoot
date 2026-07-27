package org.jeecg.modules.airag.practice.eval.service;

import org.junit.jupiter.api.Test;
import org.jeecg.modules.airag.practice.tool.vo.ToolChatResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiEvalScorerTest {

    @Test
    void blankAnswerDoesNotReceiveKeywordScore() {
        assertEquals(BigDecimal.ZERO, AiEvalScorer.scoreKeywords("", "[\"Redis\"]"));
    }

    @Test
    void unexpectedToolCallFailsNoToolCase() {
        ToolChatResponse.ToolCallDetail call = ToolChatResponse.ToolCallDetail.builder()
                .toolCode("queryOrder")
                .build();

        assertEquals(BigDecimal.ZERO, AiEvalScorer.scoreTool(List.of(call), ""));
        assertEquals(new BigDecimal("100"), AiEvalScorer.scoreTool(List.of(), ""));
    }

    @Test
    void malformedGoldJsonFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> AiEvalScorer.scoreKeywords("answer", "not-json"));
        assertThrows(IllegalArgumentException.class,
                () -> AiEvalScorer.scoreParams(List.of(), "queryOrder", "not-json"));
    }

    @Test
    void parametersAreScoredOnlyOnExpectedTool() {
        ToolChatResponse.ToolCallDetail wrongTool = ToolChatResponse.ToolCallDetail.builder()
                .toolCode("queryUser")
                .inputParams("{\"orderCode\":\"A100\"}")
                .build();

        assertEquals(BigDecimal.ZERO, AiEvalScorer.scoreParams(
                List.of(wrongTool), "queryOrder", "{\"orderCode\":\"A100\"}"));
    }
}
