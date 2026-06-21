package org.jeecg.modules.airag.practice.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.jeecg.modules.airag.practice.chat.entity.AiChatSession;

/**
 * AI对话会话 Mapper
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-20
 */
@Mapper
public interface AiChatSessionMapper extends BaseMapper<AiChatSession> {
}
