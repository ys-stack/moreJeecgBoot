package org.jeecg.modules.airag.practice.doc.service;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.dto.PythonParseResult;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;

/**
 * Python doc-parser-service HTTP 客户端
 *
 * 通过 RestTemplate 调用 Python 解析服务的 /parse/file 接口，
 * 将文件以 multipart/form-data 形式发送，返回解析结果。
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Slf4j
@Component
public class DocParserClient {

    @Resource
    private PracticeVectorConfig config;

    @Resource(name = "practiceParserRestTemplate")
    private RestTemplate parserRestTemplate;

    /**
     * 调用 Python 服务解析单个文件
     *
     * @param file 上传的文件
     * @return 解析结果（包含分片列表）
     */
    public PythonParseResult parseFile(MultipartFile file) {
        String url = config.getParser().getUrl() + "/parse/file";
        String fileName = file.getOriginalFilename();

        try {
            // 构建 multipart 请求体
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            // 使用 ByteArrayResource 包装文件内容，重写 getFilename() 以传递原始文件名
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            body.add("file", resource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            log.info("调用 Python 解析服务: url={}, fileName={}", url, fileName);
            ResponseEntity<PythonParseResult> response = parserRestTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, PythonParseResult.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PythonParseResult result = response.getBody();
                log.info("Python 解析完成: fileName={}, chunks={}", fileName,
                        result.getChunkCount());
                return result;
            } else {
                throw new RuntimeException("Python 解析服务返回异常状态: " + response.getStatusCode());
            }

        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("Python 解析服务不可用: url={}, error={}", url, e.getMessage());
            throw new RuntimeException("文档解析服务不可用，请确认 Python 服务已启动: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("调用 Python 解析服务失败: fileName={}, error={}", fileName, e.getMessage());
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }
}
