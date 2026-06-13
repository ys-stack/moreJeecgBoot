package org.jeecg.modules.airag.practice.log.controller;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.airag.practice.log.entity.AiModelCallLog;
import org.jeecg.modules.airag.practice.log.service.IAiModelCallLogService;
import org.jeecg.modules.airag.practice.log.vo.AiCallStatVO;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * AI 模型调用日志
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-13
 * @Version: V1.0
 */
@Slf4j
@Tag(name = "AI模型调用日志")
@RestController
@RequestMapping("/ai/callLog")
public class AiModelCallLogController {

    @Autowired
    private IAiModelCallLogService aiModelCallLogService;

    /**
     * 分页列表查询
     */
    @AutoLog(value = "AI调用日志-分页列表查询")
    @Operation(summary = "AI调用日志-分页列表查询")
    @GetMapping(value = "/list")
    public Result<IPage<AiModelCallLog>> queryPageList(AiModelCallLog query,
                                                       @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                       @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                       HttpServletRequest req) {
        QueryWrapper<AiModelCallLog> queryWrapper = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        queryWrapper.orderByDesc("create_time");
        Page<AiModelCallLog> page = new Page<>(pageNo, pageSize);
        IPage<AiModelCallLog> pageList = aiModelCallLogService.page(page, queryWrapper);
        return Result.ok(pageList);
    }

    /**
     * 添加
     */
    @AutoLog(value = "AI调用日志-添加")
    @Operation(summary = "AI调用日志-添加")
    @PostMapping(value = "/add")
    public Result<AiModelCallLog> add(@RequestBody AiModelCallLog aiModelCallLog) {
        aiModelCallLogService.save(aiModelCallLog);
        return Result.ok("添加成功！");
    }

    /**
     * 编辑
     */
    @AutoLog(value = "AI调用日志-编辑")
    @Operation(summary = "AI调用日志-编辑")
    @RequestMapping(value = "/edit", method = {RequestMethod.PUT, RequestMethod.POST})
    public Result<AiModelCallLog> edit(@RequestBody AiModelCallLog aiModelCallLog) {
        AiModelCallLog entity = aiModelCallLogService.getById(aiModelCallLog.getId());
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        aiModelCallLogService.updateById(aiModelCallLog);
        return Result.ok("修改成功！");
    }

    /**
     * 通过id删除
     */
    @AutoLog(value = "AI调用日志-通过id删除")
    @Operation(summary = "AI调用日志-通过id删除")
    @DeleteMapping(value = "/delete")
    public Result<?> delete(@RequestParam(name = "id") String id) {
        aiModelCallLogService.removeById(id);
        return Result.ok("删除成功！");
    }

    /**
     * 批量删除
     */
    @AutoLog(value = "AI调用日志-批量删除")
    @Operation(summary = "AI调用日志-批量删除")
    @DeleteMapping(value = "/deleteBatch")
    public Result<?> deleteBatch(@RequestParam(name = "ids") String ids) {
        this.aiModelCallLogService.removeByIds(Arrays.asList(ids.split(",")));
        return Result.ok("批量删除成功！");
    }

    /**
     * 通过id查询
     */
    @AutoLog(value = "AI调用日志-通过id查询")
    @Operation(summary = "AI调用日志-通过id查询")
    @GetMapping(value = "/queryById")
    public Result<AiModelCallLog> queryById(@RequestParam(name = "id") String id) {
        AiModelCallLog entity = aiModelCallLogService.getById(id);
        if (entity == null) {
            return Result.error("未找到对应实体");
        }
        return Result.ok(entity);
    }

    /**
     * 导出excel
     */
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(AiModelCallLog query, HttpServletRequest request, HttpServletResponse response) {
        QueryWrapper<AiModelCallLog> queryWrapper = null;
        try {
            String paramsStr = request.getParameter("paramsStr");
            if (oConvertUtils.isNotEmpty(paramsStr)) {
                String deString = URLDecoder.decode(paramsStr, "UTF-8");
                query = JSON.parseObject(deString, AiModelCallLog.class);
            }
            queryWrapper = QueryGenerator.initQueryWrapper(query, request.getParameterMap());
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        queryWrapper.orderByDesc("create_time");
        ModelAndView mv = new ModelAndView(new JeecgEntityExcelView());
        List<AiModelCallLog> pageList = aiModelCallLogService.list(queryWrapper);
        mv.addObject(NormalExcelConstants.FILE_NAME, "AI模型调用日志");
        mv.addObject(NormalExcelConstants.CLASS, AiModelCallLog.class);
        mv.addObject(NormalExcelConstants.PARAMS, new ExportParams("AI模型调用日志数据", "导出信息", org.jeecgframework.poi.excel.entity.enmus.ExcelType.XSSF));
        mv.addObject(NormalExcelConstants.DATA_LIST, pageList);
        return mv;
    }

    /**
     * 通过excel导入数据
     */
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
        Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
        for (Map.Entry<String, MultipartFile> entity : fileMap.entrySet()) {
            MultipartFile file = entity.getValue();
            ImportParams params = new ImportParams();
            params.setTitleRows(0);
            params.setHeadRows(1);
            params.setNeedSave(true);
            try {
                List<AiModelCallLog> list = ExcelImportUtil.importExcel(file.getInputStream(), AiModelCallLog.class, params);
                aiModelCallLogService.saveBatch(list);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                return Result.error("文件导入失败:" + e.getMessage());
            }
        }
        return Result.ok("文件导入成功！");
    }

    // ===================== Day 4 统计接口 =====================

    /**
     * 今日调用统计
     * 返回：今日调用次数、总token、平均耗时、成功/失败次数、总费用
     */
    @AutoLog(value = "AI调用日志-今日统计")
    @Operation(summary = "今日调用统计（调用次数、总token、平均耗时）")
    @GetMapping(value = "/stat/today")
    public Result<AiCallStatVO> todayStat() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        AiCallStatVO stat = aiModelCallLogService.statByDateRange(today, today, null);
        stat.setStatDate(today);
        return Result.ok(stat);
    }

    /**
     * 按日期范围统计
     */
    @AutoLog(value = "AI调用日志-日期范围统计")
    @Operation(summary = "按日期范围统计（支持按用户筛选）")
    @GetMapping(value = "/stat/range")
    public Result<AiCallStatVO> rangeStat(@RequestParam(name = "startDate") String startDate,
                                          @RequestParam(name = "endDate") String endDate,
                                          @RequestParam(name = "userId", required = false) String userId) {
        AiCallStatVO stat = aiModelCallLogService.statByDateRange(startDate, endDate, userId);
        stat.setStatDate(startDate + " ~ " + endDate);
        return Result.ok(stat);
    }

    /**
     * 按模型分组统计
     */
    @AutoLog(value = "AI调用日志-按模型统计")
    @Operation(summary = "按模型分组统计（各模型的调用次数、token、费用）")
    @GetMapping(value = "/stat/byModel")
    public Result<List<AiCallStatVO>> statByModel(@RequestParam(name = "startDate", required = false) String startDate,
                                                   @RequestParam(name = "endDate", required = false) String endDate) {
        List<AiCallStatVO> list = aiModelCallLogService.statGroupByModel(startDate, endDate);
        return Result.ok(list);
    }

    /**
     * 调用趋势（按天）
     */
    @AutoLog(value = "AI调用日志-每日趋势")
    @Operation(summary = "按天统计调用趋势（用于Dashboard图表展示）")
    @GetMapping(value = "/stat/dailyTrend")
    public Result<List<AiCallStatVO>> dailyTrend(@RequestParam(name = "startDate", required = false) String startDate,
                                                  @RequestParam(name = "endDate", required = false) String endDate) {
        // 默认最近 7 天
        if (oConvertUtils.isEmpty(startDate)) {
            startDate = LocalDate.now().minusDays(6).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if (oConvertUtils.isEmpty(endDate)) {
            endDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        List<AiCallStatVO> list = aiModelCallLogService.statDailyTrend(startDate, endDate);
        return Result.ok(list);
    }
}
