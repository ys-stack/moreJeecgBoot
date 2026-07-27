package org.jeecg.modules.airag.practice.doc.service;

import org.jeecg.modules.airag.practice.doc.vo.BatchParseResultVO;

/**
 * 批量文档解析服务接口
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
public interface BatchParseService {

    /**
     * 文件上传数据载体（脱离 MultipartFile，避免异步线程中 HTTP 请求已结束时文件流失效）
     */
    record FileUpload(String fileName, byte[] content, long size) {}

    /**
     * 批量上传文件，调用 Python 服务解析，存储到 MySQL + ES
     *
     * @param files           文件数据数组（已在 Controller 层从 MultipartFile 读取为 byte[]）
     * @param knowledgeBaseId 目标知识库ID
     * @return 批量解析结果
     */
    BatchParseResultVO batchUploadAndParse(FileUpload[] files, String knowledgeBaseId, String operatorId);
}
