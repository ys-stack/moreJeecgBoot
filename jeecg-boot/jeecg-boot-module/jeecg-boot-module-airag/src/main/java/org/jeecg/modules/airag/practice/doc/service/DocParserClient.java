package org.jeecg.modules.airag.practice.doc.service;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.dto.PythonParseResult;
import org.jeecg.modules.airag.practice.vector.config.PracticeVectorConfig;
import org.jeecg.modules.airag.practice.doc.service.BatchParseService.FileUpload;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python doc-parser-service HTTP 客户端（内置简易熔断器）
 *
 * 通过 RestTemplate 调用 Python 解析服务的 /parse/file 接口，
 * 将文件以 multipart/form-data 形式发送，返回解析结果。
 *
 * 熔断策略（无需外部依赖）：
 * - CLOSED: 正常状态，连续失败 3 次后切换到 OPEN
 * - OPEN: 拒绝所有请求（直接抛异常），60 秒后切换到 HALF_OPEN
 * - HALF_OPEN: 允许一次试探请求，成功则恢复 CLOSED，失败则回到 OPEN
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

    // ==================== 简易熔断器状态 ====================

    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    /** 连续失败阈值：达到后熔断 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 熔断冷却时间（毫秒）：之后进入半开状态 */
    private static final long COOLDOWN_MS = 60_000;

    /**
     * 调用 Python 服务解析单个文件（带熔断保护）
     *
     * @param file 文件数据（字节数组，已在 HTTP 线程预读取）
     * @return 解析结果（包含分片列表）
     */
    public PythonParseResult parseFile(FileUpload file) {
        CircuitState state = circuitState.get();

        // OPEN 状态：检查是否可以进入半开
        if (state == CircuitState.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime.get() >= COOLDOWN_MS) {
                if (circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                    log.info("[熔断器] 冷却期结束，进入 HALF_OPEN 状态，允许一次试探请求");
                    state = CircuitState.HALF_OPEN;
                } else {
                    state = circuitState.get();
                }
            }
            if (state == CircuitState.OPEN) {
                throw new RuntimeException("文档解析服务熔断中（Python 服务不可用），请稍后重试");
            }
        }

        // 执行实际调用
        try {
            PythonParseResult result = doParseFile(file);
            // 成功：重置熔断器
            if (failureCount.get() > 0) {
                failureCount.set(0);
                circuitState.set(CircuitState.CLOSED);
                log.info("[熔断器] 调用成功，恢复 CLOSED 状态");
            }
            return result;
        } catch (Exception e) {
            int failures = failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());
            if (failures >= FAILURE_THRESHOLD) {
                circuitState.set(CircuitState.OPEN);
                log.error("[熔断器] 连续失败 {} 次，进入 OPEN 状态（{}s 后自动恢复）",
                        failures, COOLDOWN_MS / 1000);
            }
            throw e;
        }
    }

    /**
     * 实际调用 Python 解析服务
     */
    private PythonParseResult doParseFile(FileUpload file) {
        String url = config.getParser().getUrl() + "/parse/file";
        String fileName = file.fileName();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(file.content()) {
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
                log.info("Python 解析完成: fileName={}, chunks={}", fileName, result.getChunkCount());
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
