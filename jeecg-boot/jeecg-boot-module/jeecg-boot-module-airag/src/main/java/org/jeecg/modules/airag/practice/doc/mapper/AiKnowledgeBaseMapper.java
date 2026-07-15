package org.jeecg.modules.airag.practice.doc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;

/**
 * AI知识库 Mapper
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Mapper
public interface AiKnowledgeBaseMapper extends BaseMapper<AiKnowledgeBase> {

    @Update("""
            UPDATE ai_knowledge_base
            SET cache_version = COALESCE(cache_version, 0) + 1,
                update_time = NOW()
            WHERE id = #{knowledgeBaseId}
            """)
    int incrementCacheVersion(@Param("knowledgeBaseId") String knowledgeBaseId);
}
