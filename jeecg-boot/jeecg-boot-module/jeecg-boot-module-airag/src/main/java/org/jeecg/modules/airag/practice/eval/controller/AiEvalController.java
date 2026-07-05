package org.jeecg.modules.airag.practice.eval.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalDataset;
import org.jeecg.modules.airag.practice.eval.entity.AiEvalResult;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalDatasetService;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalResultService;
import org.jeecg.modules.airag.practice.eval.service.IAiEvalRunnerService;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalReportVO;
import org.jeecg.modules.airag.practice.eval.vo.AiEvalRunRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * AI评测管理Controller。
 * 提供评测集和评测结果的基础管理接口。
 */
@Slf4j
@Tag(name = "AI评测管理")
@RestController
@RequestMapping("/practice/eval")
public class AiEvalController {

    /** 评测集基础服务 */
    @Resource
    private IAiEvalDatasetService evalDatasetService;

    /** 评测结果基础服务 */
    @Resource
    private IAiEvalResultService evalResultService;

    @Resource
    private IAiEvalRunnerService evalRunnerService;

    // ==================== Dataset CRUD ====================

    /**
     * 分页查询评测用例列表。
     */
    @AutoLog(value = "AI评测集-分页列表")
    @Operation(summary = "AI评测集分页列表")
    @GetMapping("/dataset/list")
    public Result<IPage<AiEvalDataset>> datasetList(AiEvalDataset query,
                                                    @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                    @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                    HttpServletRequest req) {
        QueryWrapper<AiEvalDataset> queryWrapper = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        queryWrapper.orderByAsc("case_code");
        Page<AiEvalDataset> page = new Page<>(pageNo, pageSize);
        return Result.ok(evalDatasetService.page(page, queryWrapper));
    }

    /**
     * 查询启用状态的评测用例。
     */
    @AutoLog(value = "AI评测集-启用列表")
    @Operation(summary = "查询启用的AI评测用例")
    @GetMapping("/dataset/enabled")
    public Result<List<AiEvalDataset>> enabledDataset(@RequestParam(name = "evalType", required = false) String evalType) {
        return Result.ok(evalDatasetService.listEnabled(evalType));
    }

    /**
     * 根据ID查询评测用例详情。
     */
    @AutoLog(value = "AI评测集-详情")
    @Operation(summary = "查询AI评测用例详情")
    @GetMapping("/dataset/queryById")
    public Result<AiEvalDataset> queryDatasetById(@RequestParam(name = "id") String id) {
        AiEvalDataset entity = evalDatasetService.getById(id);
        if (entity == null) {
            return Result.error("未找到对应评测用例");
        }
        return Result.ok(entity);
    }

    /**
     * 根据用例编码查询评测用例。
     */
    @AutoLog(value = "AI评测集-按编码查询")
    @Operation(summary = "按用例编码查询AI评测用例")
    @GetMapping("/dataset/queryByCode")
    public Result<AiEvalDataset> queryDatasetByCode(@RequestParam(name = "caseCode") String caseCode) {
        AiEvalDataset entity = evalDatasetService.getByCaseCode(caseCode);
        if (entity == null) {
            return Result.error("未找到对应评测用例");
        }
        return Result.ok(entity);
    }

    /**
     * 新增评测用例。
     */
    @AutoLog(value = "AI评测集-添加")
    @Operation(summary = "添加AI评测用例")
    @PostMapping("/dataset/add")
    public Result<?> addDataset(@RequestBody AiEvalDataset dataset) {
        fillDatasetDefaults(dataset, true);
        evalDatasetService.save(dataset);
        return Result.ok("添加成功！");
    }

    /**
     * 编辑评测用例。
     */
    @AutoLog(value = "AI评测集-编辑")
    @Operation(summary = "编辑AI评测用例")
    @RequestMapping(value = "/dataset/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<?> editDataset(@RequestBody AiEvalDataset dataset) {
        fillDatasetDefaults(dataset, false);
        evalDatasetService.updateById(dataset);
        return Result.ok("修改成功！");
    }

    /**
     * 删除单条评测用例。
     */
    @AutoLog(value = "AI评测集-删除")
    @Operation(summary = "删除AI评测用例")
    @DeleteMapping("/dataset/delete")
    public Result<?> deleteDataset(@RequestParam(name = "id") String id) {
        evalDatasetService.removeById(id);
        return Result.ok("删除成功！");
    }

    /**
     * 批量删除评测用例。
     */
    @AutoLog(value = "AI评测集-批量删除")
    @Operation(summary = "批量删除AI评测用例")
    @DeleteMapping("/dataset/deleteBatch")
    public Result<?> deleteBatchDataset(@RequestParam(name = "ids") String ids) {
        evalDatasetService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.ok("批量删除成功！");
    }

    // ==================== Result CRUD ====================

    /**
     * 分页查询评测结果列表。
     */
    @AutoLog(value = "AI评测结果-分页列表")
    @Operation(summary = "AI评测结果分页列表")
    @GetMapping("/result/list")
    public Result<IPage<AiEvalResult>> resultList(AiEvalResult query,
                                                  @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                  @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                  HttpServletRequest req) {
        QueryWrapper<AiEvalResult> queryWrapper = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        queryWrapper.orderByDesc("create_time");
        Page<AiEvalResult> page = new Page<>(pageNo, pageSize);
        return Result.ok(evalResultService.page(page, queryWrapper));
    }

    /**
     * 根据runId查询一次评测运行的全部结果。
     */
    @AutoLog(value = "AI评测结果-运行明细")
    @Operation(summary = "按runId查询AI评测结果")
    @GetMapping("/result/run/{runId}")
    public Result<List<AiEvalResult>> resultByRunId(@PathVariable String runId) {
        return Result.ok(evalResultService.listByRunId(runId));
    }

    /**
     * 根据ID查询评测结果详情。
     */
    @AutoLog(value = "AI评测结果-详情")
    @Operation(summary = "查询AI评测结果详情")
    @GetMapping("/result/queryById")
    public Result<AiEvalResult> queryResultById(@RequestParam(name = "id") String id) {
        AiEvalResult entity = evalResultService.getById(id);
        if (entity == null) {
            return Result.error("未找到对应评测结果");
        }
        return Result.ok(entity);
    }

    /**
     * 新增评测结果。
     */
    @AutoLog(value = "AI评测结果-添加")
    @Operation(summary = "添加AI评测结果")
    @PostMapping("/result/add")
    public Result<?> addResult(@RequestBody AiEvalResult result) {
        fillResultDefaults(result);
        evalResultService.save(result);
        return Result.ok("添加成功！");
    }

    /**
     * 删除单条评测结果。
     */
    @AutoLog(value = "AI评测结果-删除")
    @Operation(summary = "删除AI评测结果")
    @DeleteMapping("/result/delete")
    public Result<?> deleteResult(@RequestParam(name = "id") String id) {
        evalResultService.removeById(id);
        return Result.ok("删除成功！");
    }

    /**
     * 批量删除评测结果。
     */
    @AutoLog(value = "AI评测结果-批量删除")
    @Operation(summary = "批量删除AI评测结果")
    @DeleteMapping("/result/deleteBatch")
    public Result<?> deleteBatchResult(@RequestParam(name = "ids") String ids) {
        evalResultService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.ok("批量删除成功！");
    }

    /**
     * 删除某次评测运行下的全部结果。
     */
    @AutoLog(value = "AI评测结果-按runId删除")
    @Operation(summary = "按runId删除AI评测结果")
    @DeleteMapping("/result/deleteByRunId")
    public Result<?> deleteResultByRunId(@RequestParam(name = "runId") String runId) {
        evalResultService.removeByRunId(runId);
        return Result.ok("删除成功！");
    }

    /** 一键执行评测 */
    @PostMapping("/run")
    public Result<AiEvalReportVO> run(@RequestBody AiEvalRunRequest request) {
        LoginUser user = (LoginUser) SecurityUtils.getSubject().getPrincipal();
        return Result.ok(evalRunnerService.run(request, user.getId()));
    }

    /** 查看评测报告 */
    @GetMapping("/report/{runId}")
    public Result<AiEvalReportVO> report(@PathVariable String runId) {
        return Result.ok(evalRunnerService.report(runId));
    }

    /** 对比两次评测结果 */
    @GetMapping("/compare")
    public Result<Map<String, Object>> compare(@RequestParam String baseRunId,
                                               @RequestParam String targetRunId) {
        return Result.ok(evalRunnerService.compare(baseRunId, targetRunId));
    }

    /**
     * 填充评测用例默认值。
     */
    private void fillDatasetDefaults(AiEvalDataset dataset, boolean isCreate) {
        if (dataset.getStatus() == null) {
            dataset.setStatus(1);
        }
        if (dataset.getWeight() == null) {
            dataset.setWeight(BigDecimal.ONE);
        }
        if (dataset.getExpectedReject() == null) {
            dataset.setExpectedReject(0);
        }
        if (dataset.getDifficulty() == null) {
            dataset.setDifficulty("normal");
        }
        if (isCreate && dataset.getCreateTime() == null) {
            dataset.setCreateTime(new Date());
        }
        if (!isCreate) {
            dataset.setUpdateTime(new Date());
        }
    }

    /**
     * 填充评测结果默认值。
     */
    private void fillResultDefaults(AiEvalResult result) {
        if (result.getStatus() == null) {
            result.setStatus("success");
        }
        if (result.getCreateTime() == null) {
            result.setCreateTime(new Date());
        }
    }
}
