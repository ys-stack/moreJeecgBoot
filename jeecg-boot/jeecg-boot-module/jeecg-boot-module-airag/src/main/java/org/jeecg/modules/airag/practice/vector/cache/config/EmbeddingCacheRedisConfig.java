package org.jeecg.modules.airag.practice.vector.cache.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/*
 * @Author: ys
 * @Date: 2026/7/15 16:52
 * @DESC: Redis 二进制配置
 */
@Configuration
public class EmbeddingCacheRedisConfig {

    @Bean("embeddingVectorRedisTemplate")
    public RedisTemplate<String, byte[]> embeddingVectorRedisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        RedisSerializer<byte[]> valueSerializer = RedisSerializer.byteArray();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);

        template.afterPropertiesSet();
        return template;
    }
}