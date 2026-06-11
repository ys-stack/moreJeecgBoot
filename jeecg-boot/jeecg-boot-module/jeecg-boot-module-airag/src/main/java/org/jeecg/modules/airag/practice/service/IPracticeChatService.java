package org.jeecg.modules.airag.practice.service;

import org.jeecg.modules.airag.practice.vo.PracticeChatRequest;
import org.jeecg.modules.airag.practice.vo.PracticeChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 练习用 - AI 聊天服务接口
 */
public interface IPracticeChatService {

    /**
     * 普通聊天：发送问题，返回完整回答
     */
    PracticeChatResponse chat(PracticeChatRequest request);

    /**
     * 流式聊天：发送问题，SSE 逐字返回
     */
    SseEmitter chatStream(PracticeChatRequest request);

    /**
     * 结构化输出：发送需求文本，返回结构化 JSON
     */
    PracticeChatResponse chatStructured(PracticeChatRequest request);
}
