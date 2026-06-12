package org.jeecg.modules.airag.practice.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.prompt.entity.AiPromptTemplate;
import org.jeecg.modules.airag.practice.prompt.mapper.AiPromptTemplateMapper;
import org.jeecg.modules.airag.practice.prompt.service.IAiPromptTemplateService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI Prompt 模板 Service 实现
 *
 * 学习笔记：
 * - ServiceImpl<Mapper, Entity> 自动注入 Mapper，不用手动 @Autowired
 * - LambdaQueryWrapper 是 MyBatis-Plus 的类型安全查询方式，比 QueryWrapper 更推荐
 * - renderTemplate 展示了 Prompt 工程的核心：变量替换
 */
@Slf4j
@Service
public class AiPromptTemplateServiceImpl
        extends ServiceImpl<AiPromptTemplateMapper, AiPromptTemplate>
        implements IAiPromptTemplateService {

    @Override
    public AiPromptTemplate getActiveByCode(String promptCode) {
        // 查询指定编码下状态=1（启用）的最新版本
        // orderByDesc(version) 确保取到最大版本号
        return this.lambdaQuery()
                .eq(AiPromptTemplate::getPromptCode, promptCode)
                .eq(AiPromptTemplate::getStatus, 1)
                .orderByDesc(AiPromptTemplate::getVersion)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public AiPromptTemplate getByCodeAndVersion(String promptCode, Integer version) {
        return this.lambdaQuery()
                .eq(AiPromptTemplate::getPromptCode, promptCode)
                .eq(AiPromptTemplate::getVersion, version)
                .one();
    }

    @Override
    public String renderTemplate(String templateId, Map<String, String> variables) {
        AiPromptTemplate template = this.getById(templateId);
        if (template == null) {
            throw new RuntimeException("模板不存在: " + templateId);
        }

        String content = template.getTemplate();

        // 变量替换：把 {变量名} 替换成实际值
        // 这是 Prompt 工程最基础的操作
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                String value = entry.getValue() != null ? entry.getValue() : "";
                content = content.replace(placeholder, value);
            }
        }

        log.debug("渲染模板 [{}] v{} | 变量数={} | 结果长度={}",
                template.getPromptCode(), template.getVersion(),
                variables != null ? variables.size() : 0,
                content.length());

        return content;
    }
}
