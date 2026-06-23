package org.jeecg.modules.airag.practice.tool.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.modules.airag.practice.tool.entity.AiToolCallLog;
import org.jeecg.modules.airag.practice.tool.entity.AiToolDefinition;
import org.jeecg.modules.airag.practice.tool.service.IAiToolCallLogService;
import org.jeecg.modules.airag.practice.tool.service.IAiToolDefinitionService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * AI工具管理 Controller
 * 提供工具定义 CRUD + 调用日志查询接口
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-23
 */
@Slf4j
@Tag(name = "AI工具管理")
@RestController
@RequestMapping("/practice/tool")
public class AiToolController {

    @Resource
    private IAiToolDefinitionService toolDefinitionService;

    @Resource
    private IAiToolCallLogService toolCallLogService;

    // ==================== 工具定义 CRUD ====================

    @AutoLog(value = "工具定义-分页列表")
    @Operation(summary = "工具定义分页列表查询")
    @GetMapping("/definition/list")
    public Result<IPage<AiToolDefinition>> definitionList(AiToolDefinition query,
                                                          @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                          @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                          HttpServletRequest req) {
        QueryWrapper<AiToolDefinition> qw = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        qw.orderByAsc("sort_order");
        Page<AiToolDefinition> page = new Page<>(pageNo, pageSize);
        IPage<AiToolDefinition> result = toolDefinitionService.page(page, qw);
        return Result.ok(result);
    }

    @AutoLog(value = "工具定义-全部启用工具")
    @Operation(summary = "查询所有启用状态的工具（不分页，供 Tool Calling 使用）")
    @GetMapping("/definition/active")
    public Result<List<AiToolDefinition>> activeTools() {
        return Result.ok(toolDefinitionService.listActiveTools());
    }

    @AutoLog(value = "工具定义-添加")
    @Operation(summary = "添加工具定义")
    @PostMapping("/definition/add")
    public Result<?> addDefinition(@RequestBody AiToolDefinition definition) {
        toolDefinitionService.save(definition);
        return Result.ok("添加成功！");
    }

    @AutoLog(value = "工具定义-编辑")
    @Operation(summary = "编辑工具定义")
    @RequestMapping(value = "/definition/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<?> editDefinition(@RequestBody AiToolDefinition definition) {
        toolDefinitionService.updateById(definition);
        return Result.ok("修改成功！");
    }

    @AutoLog(value = "工具定义-删除")
    @Operation(summary = "删除工具定义")
    @DeleteMapping("/definition/delete")
    public Result<?> deleteDefinition(@RequestParam(name = "id") String id) {
        toolDefinitionService.removeById(id);
        return Result.ok("删除成功！");
    }

    @AutoLog(value = "工具定义-批量删除")
    @Operation(summary = "批量删除工具定义")
    @DeleteMapping("/definition/deleteBatch")
    public Result<?> deleteBatchDefinition(@RequestParam(name = "ids") String ids) {
        toolDefinitionService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.ok("批量删除成功！");
    }

    // ==================== 调用日志查询 ====================

    @AutoLog(value = "工具调用日志-分页列表")
    @Operation(summary = "工具调用日志分页列表查询")
    @GetMapping("/log/list")
    public Result<IPage<AiToolCallLog>> logList(AiToolCallLog query,
                                                 @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                 @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                 HttpServletRequest req) {
        QueryWrapper<AiToolCallLog> qw = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        qw.orderByDesc("create_time");
        Page<AiToolCallLog> page = new Page<>(pageNo, pageSize);
        IPage<AiToolCallLog> result = toolCallLogService.page(page, qw);
        return Result.ok(result);
    }

    @AutoLog(value = "工具调用日志-详情")
    @Operation(summary = "查询工具调用日志详情")
    @GetMapping("/log/queryById")
    public Result<AiToolCallLog> queryLogById(@RequestParam(name = "id") String id) {
        AiToolCallLog entity = toolCallLogService.getById(id);
        if (entity == null) {
            return Result.error("未找到对应记录");
        }
        return Result.ok(entity);
    }

    @AutoLog(value = "工具调用日志-删除")
    @Operation(summary = "删除工具调用日志")
    @DeleteMapping("/log/delete")
    public Result<?> deleteLog(@RequestParam(name = "id") String id) {
        toolCallLogService.removeById(id);
        return Result.ok("删除成功！");
    }

    @AutoLog(value = "工具调用日志-批量删除")
    @Operation(summary = "批量删除工具调用日志")
    @DeleteMapping("/log/deleteBatch")
    public Result<?> deleteBatchLog(@RequestParam(name = "ids") String ids) {
        toolCallLogService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.ok("批量删除成功！");
    }
}
