package org.jeecg.modules.airag.practice.vector.cache.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.airag.practice.vector.cache.entity.AiEmbeddingCache;

@Mapper
public interface AiEmbeddingCacheMapper
        extends BaseMapper<AiEmbeddingCache> {

    @Update("UPDATE ai_embedding_cache SET last_hit_time = NOW() WHERE cache_key = #{cacheKey}")
    int touchLastHitTime(@Param("cacheKey") String cacheKey);
}
