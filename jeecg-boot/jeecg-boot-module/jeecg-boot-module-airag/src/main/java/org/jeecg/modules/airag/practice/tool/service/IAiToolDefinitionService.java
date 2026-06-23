package org.jeecg.modules.airag.practice.tool.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;

import java.util.List;

/**
 * AI工具定义 Service
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-23
 */
public interface IAiToolDefinitionService extends IService<AiToolDefinition> {

    /**
     * 查询所有启用状态的工具定义
     *
     * @return 工具定义列表（按 sort_order 排序）
     */
    List<AiToolDefinition> listActiveTools();

    /**
     * 根据工具编码查询
     *
     * @param toolCode 工具编码
     * @return 工具定义
     */
    AiToolDefinition getByToolCode(String toolCode);
}
