package org.jeecg.modules.airag.practice.prompt.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.airag.practice.prompt.entity.AiPromptTemplate;

import java.util.Map;

/**
 * AI Prompt 模板 Service 接口
 *
 * 生产级能力：
 * - Caffeine 本地缓存：getActiveByCode 高频查询走缓存，避免每次 AI 请求都打 DB
 * - 增删改自动失效缓存：保证模板变更后下次请求拿到最新版本
 * - renderByCode：一步完成"查模板 + 渲染变量"，调用方不用分两步
 * - 未替换变量检测：渲染后检查是否还有残留的 {变量名}，打 warn 日志
 */
public interface IAiPromptTemplateService extends IService<AiPromptTemplate> {

    /**
     * 根据编码获取最新启用版本模板（带缓存）
     *
     * @param promptCode 模板编码
     * @return 最新的启用版模板，没有则返回 null
     */
    AiPromptTemplate getActiveByCode(String promptCode);

    /**
     * 根据编码和指定版本号获取模板
     */
    AiPromptTemplate getByCodeAndVersion(String promptCode, Integer version);

    /**
     * 渲染模板：把变量替换成实际值
     *
     * @param templateId 模板 ID
     * @param variables  变量键值对
     * @return 渲染后的完整 Prompt 文本
     */
    String renderTemplate(String templateId, Map<String, String> variables);

    /**
     * 一步完成：按编码查最新启用模板 → 渲染变量 → 返回结果
     * 调用方不用先 getActiveByCode 再 renderTemplate，一个方法搞定。
     *
     * @param promptCode 模板编码
     * @param variables  变量键值对（可为 null）
     * @return 渲染后的 Prompt 文本
     * @throws RuntimeException 模板不存在时抛出
     */
    String renderByCode(String promptCode, Map<String, String> variables);

    /**
     * 手动失效缓存（模板变更时由 Service 内部调用，也可外部触发）
     *
     * @param promptCode 要失效的模板编码，null 则清空全部缓存
     */
    void evictCache(String promptCode);
}
