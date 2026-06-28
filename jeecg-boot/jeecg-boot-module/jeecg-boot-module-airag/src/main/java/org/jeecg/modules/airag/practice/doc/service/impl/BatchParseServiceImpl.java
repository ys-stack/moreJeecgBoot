package org.jeecg.modules.airag.practice.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.airag.practice.doc.dto.PythonParseResult;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentChunkMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiKnowledgeBaseMapper;
import org.jeecg.modules.airag.practice.doc.service.BatchParseService;
import org.jeecg.modules.airag.practice.doc.service.BatchParseService.FileUpload;
import org.jeecg.modules.airag.practice.doc.service.DocParserClient;
import org.jeecg.modules.airag.practice.doc.vo.*;
import org.jeecg.modules.airag.practice.threadpool.PracticeThreadPool;
import org.jeecg.modules.airag.practice.vector.service.VectorStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 批量文档解析服务实现
 *
 * 使用 FileUpload（byte[]）而非 MultipartFile，
 * 避免异步线程中 HTTP 请求已结束导致文件流失效卡死。
 *
 * 流程：
 * 1. 校验知识库
 * 2. 每个文件并行处理（practiceAsyncPool）：
 *    - 创建 AiDocument 记录
 *    - 保存文件到磁盘
 *    - 调 Python 服务解析
 *    - 存储分片到 MySQL
 *    - 向量化入 ES
 * 3. 汇总结果返回
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-22
 */
@Slf4j
@Service
public class BatchParseServiceImpl implements BatchParseService {

    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Resource
    private AiDocumentChunkMapper aiDocumentChunkMapper;

    @Autowired
    private DocParserClient docParserClient;

    @Autowired(required = false)
    private VectorStoreService vectorStoreService;

    @Resource
    @Qualifier("practiceAsyncPool")
    private PracticeThreadPool asyncPool;

    @Value("${jeecg.path.upload:/opt/upFiles}")
    private String uploadPath;

    /** 支持的文件后缀（与 Python 服务对齐） */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".md", ".markdown", ".txt", ".pdf", ".docx");

    /** 最大文件大小：50MB（PDF/DOCX 可能较大） */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /** 默认知识库名称 */
    private static final String DEFAULT_KB_NAME = "默认知识库";

    /** 单文件处理超时：5 分钟 */
    private static final long FILE_TIMEOUT_MINUTES = 5;

    @Override
    public BatchParseResultVO batchUploadAndParse(FileUpload[] files, String knowledgeBaseId) {
        // ========== 1. 校验知识库 ==========
        AiKnowledgeBase kb = getOrCreateKnowledgeBase(knowledgeBaseId);
        String kbId = kb.getId();

        log.info("批量解析开始: 文件数={}, 知识库={}, poolState=[core={}, active={}, queue={}]",
                files.length, kb.getName(),
                asyncPool.getCorePoolSize(), asyncPool.getActiveCount(), asyncPool.getQueue().size());

        // ========== 2. 提交任务到线程池（用 submit 而非 CompletableFuture，避免 afterExecute 死锁） ==========
        List<Future<ProcessResult>> futures = new ArrayList<>(files.length);
        for (int i = 0; i < files.length; i++) {
            FileUpload file = files[i];
            final int index = i + 1;
            Future<ProcessResult> future = asyncPool.submit(() -> {
                log.debug("开始处理第 {} 个文件: {}", index, file.fileName());
                return processSingleFile(file, kbId);
            });
            futures.add(future);
        }

        log.info("已提交 {} 个任务到线程池, queueSize={}", futures.size(), asyncPool.getQueue().size());

        // ========== 3. 逐个收集结果（带超时） ==========
        List<SingleFileResultVO> results = new ArrayList<>();
        List<BatchParseErrorVO> errors = new ArrayList<>();

        for (int i = 0; i < futures.size(); i++) {
            try {
                ProcessResult pr = futures.get(i).get(FILE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                if (pr.success) {
                    results.add(pr.resultVO);
                } else {
                    errors.add(BatchParseErrorVO.builder()
                            .fileName(pr.fileName)
                            .error(pr.errorMessage)
                            .build());
                }
            } catch (TimeoutException e) {
                log.error("第 {} 个文件处理超时(>{}min)", i + 1, FILE_TIMEOUT_MINUTES);
                errors.add(BatchParseErrorVO.builder()
                        .fileName("file#" + (i + 1))
                        .error("处理超时（" + FILE_TIMEOUT_MINUTES + "min）")
                        .build());
            } catch (Exception e) {
                log.error("第 {} 个文件处理异常: {}", i + 1, e.getMessage());
                errors.add(BatchParseErrorVO.builder()
                        .fileName("file#" + (i + 1))
                        .error(e.getMessage())
                        .build());
            }
        }

        log.info("批量解析完成: 总数={}, 成功={}, 失败={}", files.length, results.size(), errors.size());

        return BatchParseResultVO.builder()
                .totalFiles(files.length)
                .successCount(results.size())
                .failedCount(errors.size())
                .results(results)
                .errors(errors)
                .build();
    }

    // ==================== 单文件处理（独立 try-catch） ====================

    /**
     * 处理单个文件：校验 → 建文档记录 → 存文件 → 调Python → 存分片 → 向量化
     * 所有异常在此方法内捕获，不向上抛出
     */
    private ProcessResult processSingleFile(FileUpload file, String kbId) {
        String fileName = file.fileName();
        ProcessResult pr = new ProcessResult();
        pr.fileName = fileName;

        try {
            // ---------- 校验 ----------
            validateFile(file);

            // ---------- 创建 AiDocument（status=parsing） ----------
            String documentId = UUID.randomUUID().toString().replace("-", "");
            AiDocument doc = new AiDocument()
                    .setId(documentId)
                    .setKnowledgeBaseId(kbId)
                    .setTitle(extractTitle(fileName))
                    .setDocType(detectDocType(fileName))
                    .setFileName(fileName)
                    .setFileSize(file.size())
                    .setStatus("parsing")
                    .setCreateTime(new Date());
            aiDocumentMapper.insert(doc);

            // ---------- 保存文件到磁盘 ----------
            String filePath = saveOriginalFile(file, documentId);
            doc.setFilePath(filePath);
            aiDocumentMapper.updateById(doc);

            // ---------- 调 Python 服务解析 ----------
            PythonParseResult parseResult = docParserClient.parseFile(file);

            // ---------- 转换并存储分片 ----------
            List<AiDocumentChunk> chunkEntities = convertToEntities(
                    parseResult.getChunks(), documentId, fileName, filePath);
            if (!chunkEntities.isEmpty()) {
                for (AiDocumentChunk chunk : chunkEntities) {
                    aiDocumentChunkMapper.insert(chunk);
                }
                log.info("分片存储完成: documentId={}, chunks={}", documentId, chunkEntities.size());
            }

            // ---------- 更新 AiDocument 状态 ----------
            int totalChars = parseResult.getTotalChars() != null ? parseResult.getTotalChars() : 0;
            int chunkCount = parseResult.getChunkCount() != null ? parseResult.getChunkCount() : chunkEntities.size();
            doc.setStatus("completed")
                    .setChunkCount(chunkCount)
                    .setTotalChars(totalChars)
                    .setUpdateTime(new Date());
            aiDocumentMapper.updateById(doc);

            // ---------- 更新知识库计数 ----------
            updateKnowledgeBaseCounts(kbId);

            // ---------- 向量化入 ES（失败时标记状态，便于后续重试） ----------
            int vectorizedCount = 0;
            if (vectorStoreService != null && !chunkEntities.isEmpty()) {
                try {
                    vectorizedCount = vectorStoreService.vectorizeAndStore(documentId, kbId, chunkEntities);
                    doc.setStatus("vectorized");
                    aiDocumentMapper.updateById(doc);
                    log.info("向量化完成: documentId={}, vectors={}", documentId, vectorizedCount);
                } catch (Exception e) {
                    log.warn("向量化失败: documentId={}, error={}", documentId, e.getMessage());
                    doc.setStatus("vectorize_failed");
                    aiDocumentMapper.updateById(doc);
                }
            }

            // ---------- 构建返回结果 ----------
            List<DocumentChunkVO> chunkPreview = parseResult.getChunks().stream()
                    .limit(20)
                    .map(c -> DocumentChunkVO.builder()
                            .chunkIndex(c.getChunkIndex())
                            .heading(c.getHeading())
                            .content(c.getContent())
                            .charCount(c.getCharCount())
                            .tokenCount(c.getTokenCount())
                            .chunkType(c.getChunkType())
                            .build())
                    .collect(Collectors.toList());

            int totalTokens = parseResult.getTotalTokens() != null ? parseResult.getTotalTokens() : 0;
            pr.success = true;
            pr.resultVO = SingleFileResultVO.builder()
                    .documentId(documentId)
                    .fileName(fileName)
                    .fileType(parseResult.getFileType())
                    .totalChars(totalChars)
                    .chunkCount(chunkCount)
                    .totalTokens(totalTokens)
                    .chunks(chunkPreview)
                    .build();

            log.info("文件处理成功: fileName={}, chunks={}", fileName, chunkCount);
            return pr;

        } catch (Exception e) {
            log.error("文件处理失败: fileName={}, error={}", fileName, e.getMessage(), e);
            pr.success = false;
            pr.errorMessage = e.getMessage();
            return pr;
        }
    }

    // ==================== 内部方法 ====================

    private void validateFile(FileUpload file) {
        if (file == null || file.content() == null || file.content().length == 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = file.fileName();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String lowerName = fileName.toLowerCase();
        boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (!validExt) {
            throw new IllegalArgumentException(
                    "仅支持 " + String.join(", ", ALLOWED_EXTENSIONS) + " 文件，当前: " + fileName);
        }
        if (file.size() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "文件大小不能超过 50MB，当前: " + String.format("%.1fMB", file.size() / 1024.0 / 1024.0));
        }
    }

    private AiKnowledgeBase getOrCreateKnowledgeBase(String knowledgeBaseId) {
        if (StringUtils.isNotBlank(knowledgeBaseId)) {
            AiKnowledgeBase kb = aiKnowledgeBaseMapper.selectById(knowledgeBaseId);
            if (kb != null) {
                return kb;
            }
            log.warn("指定知识库不存在({}), 将使用默认知识库", knowledgeBaseId);
        }

        List<AiKnowledgeBase> defaults = aiKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<AiKnowledgeBase>()
                        .eq(AiKnowledgeBase::getName, DEFAULT_KB_NAME)
                        .last("LIMIT 1"));
        if (!defaults.isEmpty()) {
            return defaults.get(0);
        }

        AiKnowledgeBase newKb = new AiKnowledgeBase()
                .setName(DEFAULT_KB_NAME)
                .setDescription("系统自动创建的默认知识库")
                .setStatus("active")
                .setDocCount(0)
                .setChunkCount(0)
                .setCreateTime(new Date());
        aiKnowledgeBaseMapper.insert(newKb);
        log.info("创建默认知识库: id={}, name={}", newKb.getId(), DEFAULT_KB_NAME);
        return newKb;
    }

    private String saveOriginalFile(FileUpload file, String documentId) {
        try {
            Path dir = Paths.get(uploadPath, "practice", "doc", documentId);
            Files.createDirectories(dir);

            // webkitdirectory 会把相对路径带进文件名（如 "docs/xxx.md"），
            // 需要提取纯文件名，避免子目录不存在导致 NoSuchFileException
            String originalName = file.fileName();
            String safeName = originalName;
            if (originalName != null) {
                int lastSep = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
                if (lastSep >= 0) {
                    safeName = originalName.substring(lastSep + 1);
                }
            }

            // 路径穿越防护：检查提取后的文件名是否包含目录遍历字符
            if (safeName == null || safeName.isBlank()
                    || safeName.contains("..") || safeName.contains("/") || safeName.contains("\\")) {
                throw new SecurityException("非法文件名（疑似路径穿越攻击）: " + originalName);
            }

            Path target = dir.resolve(safeName);
            // 二次校验：确保解析后的绝对路径仍在预期目录内
            if (!target.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize())) {
                throw new SecurityException("文件保存路径越界（路径穿越）: " + originalName);
            }

            Files.copy(new ByteArrayInputStream(file.content()), target, StandardCopyOption.REPLACE_EXISTING);
            String relativePath = "practice/doc/" + documentId + "/" + safeName;
            log.info("原始文件已保存: {}", target);
            return relativePath;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    private List<AiDocumentChunk> convertToEntities(
            List<PythonParseResult.PythonChunk> chunks, String documentId, String fileName, String filePath) {
        if (chunks == null) return Collections.emptyList();
        List<AiDocumentChunk> entities = new ArrayList<>(chunks.size());
        Date now = new Date();
        for (PythonParseResult.PythonChunk c : chunks) {
            AiDocumentChunk entity = new AiDocumentChunk()
                    .setDocumentId(documentId)
                    .setChunkIndex(c.getChunkIndex())
                    .setHeading(c.getHeading())
                    .setContent(c.getContent())
                    .setCharCount(c.getCharCount())
                    .setTokenCount(c.getTokenCount())
                    .setChunkType(c.getChunkType())
                    .setSourceFileName(fileName)
                    .setSourceFilePath(filePath)
                    .setCreateTime(now);
            entities.add(entity);
        }
        return entities;
    }

    private void updateKnowledgeBaseCounts(String knowledgeBaseId) {
        // 单条 SQL 统计文档数和分片数（替代逐文档 COUNT 的 N+1 查询）
        List<AiDocument> docs = aiDocumentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getKnowledgeBaseId, knowledgeBaseId));
        int docCount = docs.size();

        int chunkCount = 0;
        if (!docs.isEmpty()) {
            List<String> docIds = docs.stream().map(AiDocument::getId).collect(Collectors.toList());
            // 批量查询所有相关分片，一次 SQL 完成计数
            Long total = aiDocumentChunkMapper.selectCount(
                    new LambdaQueryWrapper<AiDocumentChunk>()
                            .in(AiDocumentChunk::getDocumentId, docIds));
            chunkCount = total != null ? total.intValue() : 0;
        }

        AiKnowledgeBase kb = aiKnowledgeBaseMapper.selectById(knowledgeBaseId);
        if (kb != null) {
            kb.setDocCount(docCount).setChunkCount(chunkCount).setUpdateTime(new Date());
            aiKnowledgeBaseMapper.updateById(kb);
        }
    }

    private String extractTitle(String fileName) {
        if (fileName == null) return "未命名文档";
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    private String detectDocType(String fileName) {
        if (fileName == null) return "unknown";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".docx")) return "docx";
        return "unknown";
    }

    // ==================== 内部结果类 ====================

    private static class ProcessResult {
        boolean success;
        String fileName;
        String errorMessage;
        SingleFileResultVO resultVO;
    }
}
