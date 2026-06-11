package org.jeecg.modules.airag.practice.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 练习模块 - 模型配置
 * 
 * 说明：这里直接用 LangChain4j 的 OpenAI 客户端对接国产模型（DeepSeek/硅基流动/通义千问等）。
 * 国产模型 API 格式兼容 OpenAI，只需要改 baseUrl 和 apiKey。
 * 
 * 配置项写在 application-dev.yml 中：
 * practice.ai.base-url=https://api.deepseek.com
 * practice.ai.api-key=sk-xxxx
 * practice.ai.model-name=deepseek-chat
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "practice.ai")
public class PracticeAiConfig {

    /**
     * 模型 API 地址
     */
    private String baseUrl = "https://api.xiaomimimo.com/v1";

    /**
     * API Key（去模型平台注册获取）
     */
    private String apiKey = "sk-cfyt1k9ifm9y7hvua7v47044ehv42gd1hxtjyuikl3o0pzlx";

    /**
     * 模型名称
     */
    private String modelName = "mimo-v2.5-pro";

    /**
     * 温度参数：0-2，越高越有创造力，越低越稳定
     */
    private Double temperature = 0.7;

    /**
     * 最大输出 token 数
     */
    private Integer maxTokens = 2048;

    /**
     * 超时时间（秒）
     */
    private Integer timeoutSeconds = 60;

    /**
     * 同步模型客户端（用于普通请求）
     */
    @Bean("practiceChatModel")
    public OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * 流式模型客户端（用于 SSE 流式输出）
     */
    @Bean("practiceStreamingChatModel")
    public OpenAiStreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
