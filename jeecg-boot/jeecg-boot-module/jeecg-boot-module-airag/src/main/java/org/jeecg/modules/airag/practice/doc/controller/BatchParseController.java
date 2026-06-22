package org.jeecg.modules.airag.practice.doc.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.practice.doc.service.BatchParseService;
import org.jeecg.modules.airag.practice.doc.vo.BatchParseResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量文档解析 Controller
 *
 * 前端上传多个文件 → Java 调用 Python 服务解析 → 存入 MySQL + ES → 返回结果
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Slf4j
@RestController
@RequestMapping("/practice/doc/batch")
@Tag(name = "批量文档解析")
public class BatchParseController {

    @Autowired
    private BatchParseService batchParseService;

    @PostMapping("/upload")
    @Operation(summary = "批量上传文件并解析入库")
    public Result<BatchParseResultVO> batchUpload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId) {

        if (files == null || files.length == 0) {
            return Result.error("请选择要解析的文件");
        }

        log.info("批量解析请求: 文件数={}, knowledgeBaseId={}", files.length, knowledgeBaseId);

        try {
            BatchParseResultVO result = batchParseService.batchUploadAndParse(files, knowledgeBaseId);
            return Result.OK(result);
        } catch (Exception e) {
            log.error("批量解析失败", e);
            return Result.error("批量解析失败: " + e.getMessage());
        }
    }
}
