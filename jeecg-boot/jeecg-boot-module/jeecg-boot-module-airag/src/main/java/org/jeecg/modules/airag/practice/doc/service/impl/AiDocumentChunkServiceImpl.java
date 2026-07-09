package org.jeecg.modules.airag.practice.doc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.airag.practice.doc.entity.AiDocument;
import org.jeecg.modules.airag.practice.doc.entity.AiDocumentChunk;
import org.jeecg.modules.airag.practice.doc.entity.AiKnowledgeBase;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentChunkMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiDocumentMapper;
import org.jeecg.modules.airag.practice.doc.mapper.AiKnowledgeBaseMapper;
import org.jeecg.modules.airag.practice.doc.parser.MarkdownParser;
import org.jeecg.modules.airag.practice.doc.service.IAiDocumentChunkService;
import org.jeecg.modules.airag.practice.doc.vo.DocumentChunkVO;
import org.jeecg.modules.airag.practice.doc.vo.DocumentUploadResultVO;
import org.jeecg.modules.airag.practice.sync.dto.EsSyncMessage;
import org.jeecg.modules.airag.practice.sync.producer.EsSyncProducer;
import org.jeecg.modules.airag.practice.vector.service.VectorStoreService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI文档分片 Service 实现
 *
 * 整合 知识库 → 文档 → 分片 的完整链路：
 * 1. 校验文件（非空、.md 后缀、大小 ≤ 10MB）
 * 2. 确保知识库存在（无则创建默认知识库）
 * 3. 创建 AiDocument 记录（status=pending）
 * 4. 保存原始文件到磁盘
 * 5. MarkdownParser 解析切分
 * 6. 批量插入 AiDocumentChunk
 * 7. 更新 AiDocument（status=completed, chunkCount, totalChars）
 * 8. 更新 AiKnowledgeBase 的冗余计数
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Slf4j
@Service
public class AiDocumentChunkServiceImpl
        extends ServiceImpl<AiDocumentChunkMapper, AiDocumentChunk>
        implements IAiDocumentChunkService {

    @Resource
    private AiDocumentMapper aiDocumentMapper;

    @Resource
    private AiKnowledgeBaseMapper aiKnowledgeBaseMapper;

    @Autowired(required = false)
    private VectorStoreService vectorStoreService;
    @Resource
    private EsSyncProducer esSyncProducer;

    @Value("${jeecg.path.upload:/opt/upFiles}")
    private String uploadPath;

    /** 允许的文件后缀 */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".md", ".markdown", ".txt");

    /** 最大文件大小：10MB */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /** 默认知识库名称 */
    private static final String DEFAULT_KB_NAME = "默认知识库";

    private final MarkdownParser markdownParser = new MarkdownParser();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentUploadResultVO uploadAndParse(MultipartFile file, String knowledgeBaseId) {
        // ========== 1. 文件校验 ==========
        validateFile(file);
        String originalFileName = file.getOriginalFilename();

        // ========== 2. 确保知识库存在 ==========
        AiKnowledgeBase kb = getOrCreateKnowledgeBase(knowledgeBaseId);

        // ========== 3. 创建 AiDocument 记录（pending 状态） ==========
        String documentId = UUID.randomUUID().toString().replace("-", "");
        AiDocument doc = new AiDocument()
                .setId(documentId)
                .setKnowledgeBaseId(kb.getId())
                .setTitle(extractTitle(originalFileName))
                .setDocType(detectDocType(originalFileName))
                .setFileName(originalFileName)
                .setFileSize(file.getSize())
                .setStatus("pending")
                .setCreateTime(new Date());
        aiDocumentMapper.insert(doc);
        log.info("创建文档记录: id={}, title={}, status=pending", documentId, doc.getTitle());

        // ========== 4. 保存原始文件 ==========
        String filePath = saveOriginalFile(file, documentId);
        doc.setFilePath(filePath);
        aiDocumentMapper.updateById(doc);

        // ========== 5. 解析 + 切分 ==========
        List<DocumentChunkVO> chunkVOs;
        try (InputStream is = file.getInputStream()) {
            chunkVOs = markdownParser.parse(is);
        } catch (IOException e) {
            // 解析失败：更新文档状态为 failed
            doc.setStatus("failed").setErrorMsg("解析失败: " + e.getMessage()).setUpdateTime(new Date());
            aiDocumentMapper.updateById(doc);
            throw new RuntimeException("Markdown 文件解析失败: " + e.getMessage(), e);
        }

        if (chunkVOs.isEmpty()) {
            log.warn("文档解析后无有效分片: {}", originalFileName);
        }

        // ========== 6. 转换 + 批量存储分片 ==========
        List<AiDocumentChunk> entities = convertToEntities(chunkVOs, documentId, originalFileName, filePath);
        if (!entities.isEmpty()) {
            this.saveBatch(entities, 200);
            log.info("文档分片存储完成：documentId={}, 分片数={}", documentId, entities.size());
        }

        // ========== 7. 更新 AiDocument 状态 ==========
        int totalChars = chunkVOs.stream().mapToInt(DocumentChunkVO::getCharCount).sum();
        doc.setStatus("completed")
                .setChunkCount(chunkVOs.size())
                .setTotalChars(totalChars)
                .setUpdateTime(new Date());
        aiDocumentMapper.updateById(doc);

        // ========== 8. 更新知识库冗余计数 ==========
        updateKnowledgeBaseCounts(kb.getId());

        // ========== 9. 自动向量化异步同步至 ES ==========
        if (!entities.isEmpty()) {
            esSyncProducer.sendSyncMessage(EsSyncMessage.ACTION_INDEX, documentId, kb.getId());
            log.info("已向 ActiveMQ 发送文档异步向量化消息: documentId={}", documentId);
        }
        int vectorizedCount = 0;

        // ========== 10. 构建返回结果 ==========
        int totalTokens = chunkVOs.stream().mapToInt(DocumentChunkVO::getTokenCount).sum();
        List<DocumentChunkVO> preview = chunkVOs.stream().limit(5).collect(Collectors.toList());

        return DocumentUploadResultVO.builder()
                .documentId(documentId)
                .knowledgeBaseId(kb.getId())
                .fileName(originalFileName)
                .totalChars(totalChars)
                .chunkCount(chunkVOs.size())
                .totalTokens(totalTokens)
                .filePath(filePath)
                .vectorizedCount(vectorizedCount)
                .chunks(preview)
                .build();
    }

    @Override
    public List<AiDocumentChunk> listByDocumentId(String documentId) {
        return this.lambdaQuery()
                .eq(AiDocumentChunk::getDocumentId, documentId)
                .orderByAsc(AiDocumentChunk::getChunkIndex)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteDocumentAndChunks(String documentId) {
        // 删除分片
        int deleted = deleteChunksByDocumentId(documentId);

//update-begin---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------
        // 发送异步删除消息到 ActiveMQ 队列进行 ES 数据清理
        esSyncProducer.sendSyncMessage(EsSyncMessage.ACTION_DELETE, documentId, null);
        log.info("已发送异步删除消息到 ActiveMQ: documentId={}", documentId);
//update-end---author:ys ---date:2026-07-09  for：MySQL-ES异步同步-----------

        // 删除文档记录
        AiDocument doc = aiDocumentMapper.selectById(documentId);
        if (doc != null) {
            String kbId = doc.getKnowledgeBaseId();
            aiDocumentMapper.deleteById(documentId);
            // 更新知识库计数
            updateKnowledgeBaseCounts(kbId);
        }

        return deleted;
    }

    @Override
    public List<AiDocument> listDocumentsByKnowledgeBase(String knowledgeBaseId) {
        return aiDocumentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .orderByDesc(AiDocument::getCreateTime)
        );
    }

    // ==================== 内部方法 ====================

    /**
     * 删除某文档的所有分片
     */
    private int deleteChunksByDocumentId(String documentId) {
        long count = this.lambdaQuery()
                .eq(AiDocumentChunk::getDocumentId, documentId)
                .count();
        if (count > 0) {
            this.lambdaUpdate()
                    .eq(AiDocumentChunk::getDocumentId, documentId)
                    .remove();
        }
        return (int) count;
    }

    /**
     * 获取或创建默认知识库
     */
    private AiKnowledgeBase getOrCreateKnowledgeBase(String knowledgeBaseId) {
        if (StringUtils.isNotBlank(knowledgeBaseId)) {
            AiKnowledgeBase kb = aiKnowledgeBaseMapper.selectById(knowledgeBaseId);
            if (kb != null) {
                return kb;
            }
            log.warn("指定知识库不存在({}), 将使用默认知识库", knowledgeBaseId);
        }

        // 查找或创建默认知识库
        List<AiKnowledgeBase> defaults = aiKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<AiKnowledgeBase>()
                        .eq(AiKnowledgeBase::getName, DEFAULT_KB_NAME)
                        .last("LIMIT 1")
        );

        if (!defaults.isEmpty()) {
            return defaults.get(0);
        }

        // 创建默认知识库
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

    /**
     * 更新知识库的冗余计数（文档数 + 分片总数）
     */
    private void updateKnowledgeBaseCounts(String knowledgeBaseId) {
        List<AiDocument> docs = aiDocumentMapper.selectList(
                new LambdaQueryWrapper<AiDocument>()
                        .eq(AiDocument::getKnowledgeBaseId, knowledgeBaseId)
        );

        int docCount = docs.size();
        int chunkCount = 0;
        for (AiDocument d : docs) {
            chunkCount += Math.toIntExact(this.lambdaQuery()
                    .eq(AiDocumentChunk::getDocumentId, d.getId())
                    .count());
        }

        AiKnowledgeBase kb = aiKnowledgeBaseMapper.selectById(knowledgeBaseId);
        if (kb != null) {
            kb.setDocCount(docCount).setChunkCount(chunkCount).setUpdateTime(new Date());
            aiKnowledgeBaseMapper.updateById(kb);
        }
    }

    /**
     * 文件校验
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String lowerName = fileName.toLowerCase();
        boolean validExt = ALLOWED_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
        if (!validExt) {
            throw new IllegalArgumentException(
                    "仅支持 " + String.join(", ", ALLOWED_EXTENSIONS) + " 文件，当前: " + fileName);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "文件大小不能超过 10MB，当前: " + String.format("%.1fMB", file.getSize() / 1024.0 / 1024.0));
        }
    }

    /**
     * 保存原始文件到磁盘
     * 路径：{uploadPath}/practice/doc/{documentId}/{fileName}
     */
    private String saveOriginalFile(MultipartFile file, String documentId) {
        try {
            Path dir = Paths.get(uploadPath, "practice", "doc", documentId);
            Files.createDirectories(dir);
            Path target = dir.resolve(file.getOriginalFilename());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            String relativePath = "practice/doc/" + documentId + "/" + file.getOriginalFilename();
            log.info("原始文件已保存: {}", target);
            return relativePath;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    /**
     * VO → 实体转换
     */
    private List<AiDocumentChunk> convertToEntities(
            List<DocumentChunkVO> voList, String documentId, String fileName, String filePath) {
        List<AiDocumentChunk> entities = new ArrayList<>(voList.size());
        Date now = new Date();
        for (DocumentChunkVO vo : voList) {
            AiDocumentChunk entity = new AiDocumentChunk()
                    .setDocumentId(documentId)
                    .setChunkIndex(vo.getChunkIndex())
                    .setHeading(vo.getHeading())
                    .setContent(vo.getContent())
                    .setCharCount(vo.getCharCount())
                    .setTokenCount(vo.getTokenCount())
                    .setChunkType(vo.getChunkType())
                    .setSourceFileName(fileName)
                    .setSourceFilePath(filePath)
                    .setCreateTime(now);
            entities.add(entity);
        }
        return entities;
    }

    /**
     * 从文件名提取标题（去掉后缀）
     */
    private String extractTitle(String fileName) {
        if (fileName == null) return "未命名文档";
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    /**
     * 检测文档类型
     */
    private String detectDocType(String fileName) {
        if (fileName == null) return "unknown";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "markdown";
        if (lower.endsWith(".txt")) return "txt";
        if (lower.endsWith(".pdf")) return "pdf";
        return "unknown";
    }
}
