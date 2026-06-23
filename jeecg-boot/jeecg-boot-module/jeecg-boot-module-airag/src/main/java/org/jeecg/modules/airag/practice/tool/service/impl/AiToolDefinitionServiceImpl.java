package org.jeecg.modules.airag.practice.tool.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.mapper.AiToolDefinitionMapper;
import org.jeecg.modules.airag.practice.tool.service.IAiToolDefinitionService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI工具定义 Service 实现
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-23
 */
@Slf4j
@Service
public class AiToolDefinitionServiceImpl
        extends ServiceImpl<AiToolDefinitionMapper, AiToolDefinition>
        implements IAiToolDefinitionService {

    @Override
    public List<AiToolDefinition> listActiveTools() {
        return this.lambdaQuery()
                .eq(AiToolDefinition::getStatus, "active")
                .orderByAsc(AiToolDefinition::getSortOrder)
                .list();
    }

    @Override
    public AiToolDefinition getByToolCode(String toolCode) {
        return this.lambdaQuery()
                .eq(AiToolDefinition::getToolCode, toolCode)
                .one();
    }
}
