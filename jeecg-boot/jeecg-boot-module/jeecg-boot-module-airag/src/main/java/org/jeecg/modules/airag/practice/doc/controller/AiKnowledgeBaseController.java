package org.jeecg.modules.airag.practice.doc.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.cache.service.IKnowledgeCacheVersionService;
import org.jeecg.modules.airag.practice.doc.service.IAiKnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Date;

/**
 * AI知识库管理 Controller
 *
 * 接口清单：
 *   GET    /practice/kb/list       - 分页查询
 *   GET    /practice/kb/listAll    - 全量列表（下拉选择用）
 *   POST   /practice/kb/add        - 新增
 *   PUT    /practice/kb/edit       - 修改
 *   DELETE /practice/kb/delete     - 删除（级联删除文档和分片）
 *   GET    /practice/kb/queryById  - 按ID查询
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-16
 */
@Slf4j
@RestController
@RequestMapping("/practice/kb")
@Tag(name = "AI知识库管理", description = "知识库的增删改查")
public class AiKnowledgeBaseController
        extends JeecgController<AiKnowledgeBase, IAiKnowledgeBaseService> {

    @Autowired
    private IAiKnowledgeBaseService knowledgeBaseService;

    @Autowired
    private IKnowledgeCacheVersionService knowledgeCacheVersionService;

    /**
     * 分页查询
     * 支持按 name（模糊）、status 过滤
     */
    @GetMapping("/list")
    @Operation(summary = "分页查询知识库")
    public Result<IPage<AiKnowledgeBase>> list(
            AiKnowledgeBase query,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            HttpServletRequest req) {

        QueryWrapper<AiKnowledgeBase> qw = QueryGenerator.initQueryWrapper(query, req.getParameterMap());
        qw.orderByDesc("create_time");
        Page<AiKnowledgeBase> page = new Page<>(pageNo, pageSize);
        IPage<AiKnowledgeBase> result = knowledgeBaseService.page(page, qw);
        return Result.OK(result);
    }

    /**
     * 全量列表（不分页，用于下拉选择）
     */
    @GetMapping("/listAll")
    @Operation(summary = "全量知识库列表（下拉选择用）")
    public Result<?> listAll() {
        QueryWrapper<AiKnowledgeBase> qw = new QueryWrapper<>();
        qw.eq("status", "active");
        qw.orderByDesc("create_time");
        return Result.OK(knowledgeBaseService.list(qw));
    }

    /**
     * 新增知识库
     */
    @PostMapping("/add")
    @Operation(summary = "新增知识库")
    public Result<String> add(@RequestBody AiKnowledgeBase kb) {
        if (StringUtils.isBlank(kb.getName())) {
            return Result.error("知识库名称不能为空");
        }
        // 默认状态 active
        if (StringUtils.isBlank(kb.getStatus())) {
            kb.setStatus("active");
        }
        if (kb.getDocCount() == null) {
            kb.setDocCount(0);
        }
        if (kb.getChunkCount() == null) {
            kb.setChunkCount(0);
        }
        if (kb.getCacheVersion() == null) {
            kb.setCacheVersion(1L);
        }
        kb.setCreateTime(new Date());
        knowledgeBaseService.save(kb);
        return Result.OK("新增成功");
    }

    /**
     * 修改知识库
     */
    @PutMapping("/edit")
    @Operation(summary = "修改知识库")
    public Result<String> edit(@RequestBody AiKnowledgeBase kb) {
        if (StringUtils.isBlank(kb.getId())) {
            return Result.error("ID不能为空");
        }
        kb.setUpdateTime(new Date());
        knowledgeBaseService.updateById(kb);
        knowledgeCacheVersionService.bumpVersion(kb.getId());
        return Result.OK("修改成功");
    }

    /**
     * 删除知识库（级联删除文档和分片）
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除知识库（级联删除文档和分片）")
    public Result<String> delete(@RequestParam String ids) {
        if (oConvertUtils.isEmpty(ids)) {
            return Result.error("ID不能为空");
        }
        int totalDocs = 0;
        for (String id : Arrays.asList(ids.split(","))) {
            totalDocs += knowledgeBaseService.deleteWithDocuments(id);
        }
        return Result.OK("已删除知识库及其下 " + totalDocs + " 个文档");
    }

    /**
     * 按ID查询知识库
     */
    @GetMapping("/queryById")
    @Operation(summary = "按ID查询知识库")
    public Result<AiKnowledgeBase> queryById(@RequestParam String id) {
        AiKnowledgeBase kb = knowledgeBaseService.getById(id);
        if (kb == null) {
            return Result.error("知识库不存在");
        }
        return Result.OK(kb);
    }
}
