package org.jeecg.modules.airag.practice.prompt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.prompt.entity.AiPromptTemplate;

/**
 * AI Prompt 模板 Service 接口
 *
 * 学习笔记：
 * - IService 提供了 save / removeById / updateById / getById / list / page 等方法
 * - 自定义业务方法加在这里，比如 getByCodeAndVersion
 */
public interface IAiPromptTemplateService extends IService<AiPromptTemplate> {

    /**
     * 根据编码和版本号获取模板（取最新启用版本）
     *
     * @param promptCode 模板编码
     * @return 最新的启用版模板，没有则返回 null
     */
    AiPromptTemplate getActiveByCode(String promptCode);

    /**
     * 根据编码和指定版本号获取模板
     *
     * @param promptCode 模板编码
     * @param version    版本号
     * @return 对应版本的模板
     */
    AiPromptTemplate getByCodeAndVersion(String promptCode, Integer version);

    /**
     * 渲染模板：把变量替换成实际值
     *
     * @param templateId 模板 ID
     * @param variables  变量键值对（key=变量名, value=实际值）
     * @return 渲染后的完整 Prompt 文本
     */
    String renderTemplate(String templateId, java.util.Map<String, String> variables);
}
