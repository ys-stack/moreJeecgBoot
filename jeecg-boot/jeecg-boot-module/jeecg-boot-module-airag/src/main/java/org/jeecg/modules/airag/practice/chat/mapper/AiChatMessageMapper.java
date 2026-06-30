package org.jeecg.modules.airag.practice.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.airag.practice.chat.entity.AiChatMessage;

import java.util.List;

/**
 * AI对话消息 Mapper
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessage> {

    @Select("SELECT * FROM ("
            + " SELECT id, session_id, parent_message_id, role, content, "
            + "   prompt_tokens, completion_tokens, total_tokens, "
            + "   rag_context, rag_chunk_count, model_provider, model_name, "
            + "   duration_ms, status, error_msg, tool_calls, create_by, create_time "
            + " FROM ai_chat_message "
            + " WHERE session_id = #{sessionId} "
            + "   AND role IN ('user', 'assistant') "
            + "   AND status = 'success' "
            + " ORDER BY create_time DESC "
            + " LIMIT #{count}"
            + ") t ORDER BY create_time ASC")
    List<AiChatMessage> loadRecentMessages(@Param("sessionId") String sessionId, @Param("count") int count);
}
