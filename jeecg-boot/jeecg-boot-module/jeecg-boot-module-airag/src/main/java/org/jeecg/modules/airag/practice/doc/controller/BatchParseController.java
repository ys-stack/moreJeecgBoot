package org.jeecg.modules.airag.practice.doc.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.practice.doc.service.BatchParseService;
import org.jeecg.modules.airag.practice.doc.vo.BatchParseResultVO;
import org.jeecg.modules.airag.practice.security.KnowledgeAccessService;
import org.jeecg.modules.airag.practice.security.PracticeSecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 批量文档解析 Controller
 *
 * 前端上传多个文件 → Java 调用 Python 服务解析 → 存入 MySQL + ES → 返回结果
 *
 * 注意：在 Controller 层将 MultipartFile 读取为 byte[]，
 * 避免异步线程中 MultipartFile 的流/临时文件已被 Spring 清理导致卡死。
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

    @Autowired
    private PracticeSecurityContext securityContext;

    @Autowired
    private KnowledgeAccessService knowledgeAccessService;

    @RequiresPermissions("practice:doc:upload")
    @PostMapping("/upload")
    @Operation(summary = "批量上传文件并解析入库")
    public Result<BatchParseResultVO> batchUpload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId) {

        if (files == null || files.length == 0) {
            return Result.error("请选择要解析的文件");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return Result.error("knowledgeBaseId 不能为空");
        }

        LoginUser user = securityContext.requireUser();
        knowledgeAccessService.requireManageableKnowledgeBase(knowledgeBaseId, user);

        log.info("批量解析请求: 文件数={}, knowledgeBaseId={}", files.length, knowledgeBaseId);

        try {
            // 在 HTTP 请求线程内先把文件内容读成 byte[]，避免异步线程中 MultipartFile 失效
            BatchParseService.FileUpload[] uploads = new BatchParseService.FileUpload[files.length];
            for (int i = 0; i < files.length; i++) {
                MultipartFile f = files[i];
                uploads[i] = new BatchParseService.FileUpload(
                        f.getOriginalFilename(),
                        f.getBytes(),
                        f.getSize()
                );
            }

            BatchParseResultVO result = batchParseService.batchUploadAndParse(
                    uploads, knowledgeBaseId, user.getId());
            return Result.OK(result);
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            return Result.error("读取上传文件失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("批量解析失败", e);
            return Result.error("批量解析失败: " + e.getMessage());
        }
    }
}
