package org.jeecg.modules.airag.practice.tool.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.entity.AiToolCallLog;
import org.jeecg.modules.airag.practice.tool.mapper.AiToolCallLogMapper;
import org.jeecg.modules.airag.practice.tool.service.IAiToolCallLogService;
import org.springframework.stereotype.Service;

/**
 * AI工具调用日志 Service 实现
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-23
 */
@Slf4j
@Service
public class AiToolCallLogServiceImpl
        extends ServiceImpl<AiToolCallLogMapper, AiToolCallLog>
        implements IAiToolCallLogService {

}
