package org.jeecg.modules.airag.practice.doc.dto;

import lombok.Data;

import java.util.List;

/**
 * Python doc-parser-service 的 /parse/file 接口响应 DTO
 *
 * 字段名与 Python 返回的 JSON key 完全一致（camelCase）
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Data
public class PythonParseResult {

    private String fileName;
    private String fileType;
    private Integer totalChars;
    private Integer chunkCount;
    private Integer totalTokens;
    private List<PythonChunk> chunks;

    @Data
    public static class PythonChunk {
        private Integer chunkIndex;
        private String heading;
        private String content;
        private Integer charCount;
        private Integer tokenCount;
        private String chunkType;
    }
}
