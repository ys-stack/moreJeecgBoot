package org.jeecg.modules.airag.practice.vector.config;

import lombok.Data;
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
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "practice")
public class PracticeVectorConfig {

    private EmbedProperties embed = new EmbedProperties();
    private EsProperties es = new EsProperties();
    private ParserProperties parser = new ParserProperties();

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
        private String clusterNodes = "192.168.234.128:9200";
        /** 索引名称 */
        private String indexName = "practice_knowledge_chunks";
        /** ES 用户名（开启安全认证时使用） */
        private String username = "elastic";
        /** ES 密码 */
        private String password = "elastic123";
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
        private String url = "http://localhost:8000";
        /** 读取超时秒数 */
        private int timeoutSeconds = 120;
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
            System.out.println("[ES拦截器] username=" + username + ", password=" + (password != null ? "***" : "null"));
            if (username != null && !username.isBlank()) {
                String credentials = username + ":" + password;
                String encoded = Base64.getEncoder().encodeToString(
                        credentials.getBytes(StandardCharsets.UTF_8));
                request.getHeaders().set("Authorization", "Basic " + encoded);
                System.out.println("[ES拦截器] Authorization 头已添加");
            } else {
                System.out.println("[ES拦截器] WARNING: username 为空，未添加认证头！");
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
