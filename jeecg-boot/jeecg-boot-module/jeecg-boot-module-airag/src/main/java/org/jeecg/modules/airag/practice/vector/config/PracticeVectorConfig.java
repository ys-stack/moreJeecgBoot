package org.jeecg.modules.airag.practice.vector.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

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
        /** 集群地址，如 192.168.163.128:9200 */
        private String clusterNodes = "192.168.163.128:9200";
        /** 索引名称 */
        private String indexName = "practice_knowledge_chunks";
        /** 连接超时毫秒 */
        private int connectTimeout = 5000;
        /** 读取超时毫秒 */
        private int readTimeout = 30000;
    }

    /**
     * ES 通信用 RestTemplate
     * 和 JeecgElasticsearchTemplate 风格一致，不引入 ES Java 客户端依赖
     */
    @Bean("practiceEsRestTemplate")
    public RestTemplate practiceEsRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(es.getConnectTimeout());
        factory.setReadTimeout(es.getReadTimeout());
        return new RestTemplate(factory);
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
}
