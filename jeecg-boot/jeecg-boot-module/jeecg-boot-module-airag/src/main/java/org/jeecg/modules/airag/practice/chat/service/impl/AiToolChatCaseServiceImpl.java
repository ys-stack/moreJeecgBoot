package org.jeecg.modules.airag.practice.chat.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.jeecg.modules.airag.practice.chat.entity.AiToolChatCase;
import org.jeecg.modules.airag.practice.chat.mapper.AiToolChatCaseMapper;
import org.jeecg.modules.airag.practice.chat.service.IAiToolChatCaseService;
import org.springframework.stereotype.Service;

/**
 * Tool Calling 对话用例 Service 实现
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-29
 */
@Service
public class AiToolChatCaseServiceImpl
        extends ServiceImpl<AiToolChatCaseMapper, AiToolChatCase>
        implements IAiToolChatCaseService {
}
