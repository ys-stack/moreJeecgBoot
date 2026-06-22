package org.jeecg.modules.airag.practice.doc.service;

import org.jeecg.modules.airag.practice.doc.vo.BatchParseResultVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 批量文档解析服务接口
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
public interface BatchParseService {

    /**
     * 批量上传文件，调用 Python 服务解析，存储到 MySQL + ES
     *
     * @param files           上传的文件数组
     * @param knowledgeBaseId 目标知识库ID
     * @return 批量解析结果
     */
    BatchParseResultVO batchUploadAndParse(MultipartFile[] files, String knowledgeBaseId);
}
