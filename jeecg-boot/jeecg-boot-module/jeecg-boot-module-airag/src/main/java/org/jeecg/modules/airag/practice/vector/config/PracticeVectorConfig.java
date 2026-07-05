package org.jeecg.modules.airag.practice.vector.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

/**
 * 向量检索配置
 *
 * 绑定 application-dev.yml 中 practice.embed 和 practice.es 两组配置：
 * - embed: 硅基流动 bge-m3 Embedding API
 * - es:    ES 8.x 集群（向量存储）
 *
 * 敏感信息（apiKey、ES password）通过 yml 配置或环境变量注入，不在源码中硬编码。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "practice")
public class PracticeVectorConfig {

    private EmbedProperties embed = new EmbedProperties();
    private EsProperties es = new EsProperties();
    private ParserProperties parser = new ParserProperties();
    private RerankProperties rerank = new RerankProperties();

    /**
     * Embedding 模型配置
     */
    @Data
    public static class EmbedProperties {
        /** API Key */
        private String apiKey;
        /** API 基础地址 */
        private String baseUrl = "https://api.siliconflow.cn/v1";
        /** 模型名称 */
        private String modelName = "BAAI/bge-m3";
        /** 向量维度 */
        private int dimensions = 1024;
        /** 超时秒数 */
        private int timeoutSeconds = 30;
    }

    /**
     * Elasticsearch 配置
     */
    @Data
    public static class EsProperties {
        /** 集群地址 */
        private String clusterNodes;
        /** 索引名称 */
        private String indexName = "practice_knowledge_chunks";
        /** ES 用户名（开启安全认证时使用） */
        private String username = "elastic";
        /** ES 密码（通过 yml 或环境变量注入，不硬编码） */
        private String password;
        /** 连接超时毫秒 */
        private int connectTimeout = 5000;
        /** 读取超时毫秒 */
        private int readTimeout = 30000;
    }

    /**
     * Python 文档解析服务配置
     */
    @Data
    public static class ParserProperties {
        /** 服务地址 */
        private String url;
        /** 读取超时秒数 */
        private int timeoutSeconds = 120;
    }

    /**
     * Rerank 重排序配置
     */
    @Data
    public static class RerankProperties {
        /** 是否启用 Rerank（关闭则只用 kNN 排序） */
        private boolean enabled = true;
        /** Rerank 模型名称 */
        private String modelName = "BAAI/bge-reranker-v2-m3";
    }

    /**
     * ES 通信用 RestTemplate（自动携带 Basic Auth 认证头）
     *
     * 注意：@ConfigurationProperties 属性绑定发生在 Bean 创建之后，
     * 所以不能在 Bean 方法里用 if 判断属性值——此时属性还没绑定。
     * 必须把判断逻辑放到拦截器内部（请求时才读取属性值）。
     */
    @Bean("practiceEsRestTemplate")
    public RestTemplate practiceEsRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(es.getConnectTimeout());
        factory.setReadTimeout(es.getReadTimeout());
        RestTemplate rt = new RestTemplate(factory);

        // 拦截器在每次请求时执行，此时 @ConfigurationProperties 已绑定完成
        rt.setInterceptors(Collections.singletonList((request, body, execution) -> {
            String username = es.getUsername();
            String password = es.getPassword();
            if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
                String credentials = username + ":" + password;
                String encoded = Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8));
                request.getHeaders().set("Authorization", "Basic " + encoded);
                log.debug("[ES拦截器] Basic Auth 认证头已添加, user={}", username);
            } else {
                log.warn("[ES拦截器] ES 认证信息未配置(username={}, password={})，跳过认证",
                        username, password != null ? "***" : "null");
            }
            return execution.execute(request, body);
        }));

        return rt;
    }

    /**
     * Embedding API 调用用 RestTemplate（超时更长）
     */
    @Bean("practiceEmbedRestTemplate")
    public RestTemplate practiceEmbedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(embed.getTimeoutSeconds() * 1000);
        return new RestTemplate(factory);
    }

    /**
     * Python 文档解析服务调用用 RestTemplate（超时较长）
     */
    @Bean("practiceParserRestTemplate")
    public RestTemplate practiceParserRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(parser.getTimeoutSeconds() * 1000);
        return new RestTemplate(factory);
    }
}
