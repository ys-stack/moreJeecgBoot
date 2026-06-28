package org.jeecg.modules.airag.practice.prompt.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.airag.practice.prompt.entity.AiPromptTemplate;
import org.jeecg.modules.airag.practice.prompt.service.IAiPromptTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

/**
 * AI Prompt 模板管理
 *
 * 接口列表：
 *   GET    /practice/prompt/list          - 分页查询
 *   POST   /practice/prompt/add           - 新增
 *   PUT    /practice/prompt/edit          - 修改
 *   DELETE /practice/prompt/delete        - 删除
 *   GET    /practice/prompt/queryById     - 按 ID 查询
 *   GET    /practice/prompt/render/{id}   - 渲染模板（传变量替换）
 *   GET    /practice/prompt/active/{code} - 按编码查最新启用版本
 *
 * 学习要点：
 * - 继承 JeecgController 获得 Excel 导入导出能力
 * - QueryWrapper + QueryGenerator 实现自动条件查询（前端传什么参数就按什么条件过滤）
 * - @IgnoreAuth 让接口免登录（练习用，生产环境应加权限控制）
 */
@Tag(name = "AI Prompt 模板管理")
@Slf4j
@RestController
@RequestMapping("/practice/prompt")
public class AiPromptTemplateController
        extends JeecgController<AiPromptTemplate, IAiPromptTemplateService> {

    @Autowired
    private IAiPromptTemplateService promptTemplateService;

    /**
     * 分页查询
     * 前端传 pageNo、pageSize、以及任意字段名作为过滤条件
     */
    @GetMapping("/list")
    @Operation(summary = "分页查询")
    public Result<IPage<AiPromptTemplate>> list(
            AiPromptTemplate query,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            HttpServletRequest req) {

        // QueryGenerator 是 JeecgBoot 的查询条件自动生成器
        // 前端传什么参数（如 promptCode=xxx），它就自动拼成 WHERE 条件
        // 还支持高级语法：字段名_begin（日期起）、字段名_end（日期止）、字段名_like（模糊）
        QueryWrapper<AiPromptTemplate> qw = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        qw.orderByDesc("prompt_code", "version");
        Page<AiPromptTemplate> page = new Page<>(pageNo, pageSize);
        IPage<AiPromptTemplate> result = promptTemplateService.page(page, qw);
        return Result.OK(result);
    }

    /**
     * 新增
     */
    @PostMapping("/add")
    @Operation(summary = "新增模板")
    public Result<String> add(@RequestBody AiPromptTemplate template) {
        if (oConvertUtils.isEmpty(template.getPromptCode())) {
            return Result.error("模板编码不能为空");
        }
        if (oConvertUtils.isEmpty(template.getTemplate())) {
            return Result.error("模板内容不能为空");
        }
        // 默认版本号 1
        if (template.getVersion() == null) {
            template.setVersion(1);
        }
        // 默认启用
        if (template.getStatus() == null) {
            template.setStatus(1);
        }
        promptTemplateService.save(template);
        return Result.OK("新增成功");
    }

    /**
     * 修改
     */
    @PutMapping("/edit")
    @Operation(summary = "修改模板")
    public Result<String> edit(@RequestBody AiPromptTemplate template) {
        if (oConvertUtils.isEmpty(template.getId())) {
            return Result.error("ID 不能为空");
        }
        promptTemplateService.updateById(template);
        return Result.OK("修改成功");
    }

    /**
     * 删除（支持批量，id 用逗号分隔）
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除模板")
    public Result<String> delete(@RequestParam String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("ID 不能为空");
        }
        promptTemplateService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.OK("删除成功");
    }

    /**
     * 按 ID 查询
     */
    @GetMapping("/queryById")
    @Operation(summary = "按ID查询模板")
    public Result<AiPromptTemplate> queryById(@RequestParam String id) {
        AiPromptTemplate template = promptTemplateService.getById(id);
        if (template == null) {
            return Result.error("模板不存在");
        }
        return Result.OK(template);
    }

    /**
     * 按编码查最新启用版本
     * 这是实际 AI 调用时最常用的查询方式
     */
    @GetMapping("/active/{code}")
    @Operation(summary = "按编码查最新启用版本")
    public Result<AiPromptTemplate> getActiveByCode(@PathVariable String code) {
        AiPromptTemplate template = promptTemplateService.getActiveByCode(code);
        if (template == null) {
            return Result.error("未找到编码为 " + code + " 的启用模板");
        }
        return Result.OK(template);
    }

    /**
     * 渲染模板
     * 传入变量 Map，把模板中的 {变量名} 替换成实际值
     *
     * 示例请求：POST /practice/prompt/render/1
     * Body: {"userQuestion": "做一个用户注册功能"}
     * 返回：替换变量后的完整 Prompt 文本
     */
    @PostMapping("/render/{id}")
    @Operation(summary = "渲染模板（变量替换）")
    public Result<String> render(@PathVariable String id, @RequestBody Map<String, String> variables) {
        try {
            String rendered = promptTemplateService.renderTemplate(id, variables);
            return Result.OK(rendered);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
