package org.jeecg.modules.airag.practice.doc.parser;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.practice.doc.vo.DocumentChunkVO;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文档解析器
 *
 * 核心职责：将 Markdown 文本按 ## 标题切分为多个语义分片，超长段落自动再切。
 *
 * 切分策略：
 * 1. 以 ## 标题为主切分点，每个 ## section 是一个独立分片
 * 2. 标题路径追踪：遇到 # / ## / ### 等标题时更新路径栈，保留层级上下文
 * 3. 超长段落处理（> 500 字）：
 *    a. 优先按段落边界（空行）切分 —— 保持语义完整
 *    b. 单段仍超长 → 按句子边界（。！？.!?）切分
 *    c. 单句仍超长 → 按字数硬切（兜底）
 *
 * 面试亮点：
 * - 不引入 flexmark 等重依赖，纯正则 + 状态机实现
 * - 标题路径栈模拟浏览器书签的层级概念
 * - 三级降级切分策略：段落 → 句子 → 硬切
 *
 * @Author: jeecg-boot
 * @Date: 2026-06-15
 */
@Slf4j
public class MarkdownParser {

    /** 单个分片的最大字符数 */
    private static final int MAX_CHUNK_SIZE = 500;

    /** 硬切的兜底字符数（句子切分后仍超长的极端情况） */
    private static final int HARD_SPLIT_SIZE = 200;

    /** 匹配 Markdown 标题行：行首 1~6 个 # + 空格 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /** 匹配中英文句子结束符 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?]");

    /**
     * 解析 Markdown 输入流，返回分片列表
     *
     * @param inputStream Markdown 文件输入流
     * @return 按语义切分后的分片列表
     */
    public List<DocumentChunkVO> parse(InputStream inputStream) throws IOException {
        String content = readStream(inputStream);
        return parseContent(content);
    }

    /**
     * 解析 Markdown 字符串，返回分片列表
     *
     * @param markdown Markdown 文本
     * @return 分片列表
     */
    public List<DocumentChunkVO> parseContent(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<DocumentChunkVO> chunks = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);

        // 标题路径栈：每个元素是 [层级, 标题文本]
        // 例如：[[1, "项目概述"], [2, "背景"], [3, "市场环境"]]
        List<int[]> headingLevels = new ArrayList<>();
        List<String> headingTexts = new ArrayList<>();

        StringBuilder sectionContent = new StringBuilder();
        String currentHeading = null;
        String currentChunkType = "text";

        for (String line : lines) {
            Matcher matcher = HEADING_PATTERN.matcher(line.trim());

            if (matcher.matches()) {
                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();

                // 更新标题路径栈：弹出同级和更低级别的标题
                while (!headingLevels.isEmpty()
                        && headingLevels.get(headingLevels.size() - 1)[0] >= level) {
                    headingLevels.remove(headingLevels.size() - 1);
                    headingTexts.remove(headingTexts.size() - 1);
                }
                headingLevels.add(new int[]{level});
                headingTexts.add(title);

                // ## 是主切分点：遇到新的 ## 时，先刷出前一个 section
                if (level <= 2 && sectionContent.length() > 0) {
                    String headingPath = buildHeadingPath(headingLevels, headingTexts, level);
                    addChunks(chunks, currentHeading, sectionContent.toString(), currentChunkType);
                    sectionContent.setLength(0);
                    currentChunkType = "text";
                }

                // 更新当前标题路径
                currentHeading = buildHeadingPath(headingLevels, headingTexts, level);

                // 标题行本身也写入内容，保持文档完整性
                if (sectionContent.length() > 0) {
                    sectionContent.append("\n");
                }
                sectionContent.append(line);
            } else {
                // 普通内容行
                if (sectionContent.length() > 0) {
                    sectionContent.append("\n");
                }
                sectionContent.append(line);

                // 检测代码块类型（简单判断：``` 围栏内的内容标记为 code）
                if (line.trim().startsWith("```")) {
                    currentChunkType = "code".equals(currentChunkType) ? "text" : "code";
                }
            }
        }

        // 刷出最后一个 section
        if (sectionContent.length() > 0) {
            addChunks(chunks, currentHeading, sectionContent.toString(), currentChunkType);
        }

        // 重新编号 chunkIndex（从 0 开始连续）
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        log.info("Markdown 解析完成：共 {} 个分片", chunks.size());
        return chunks;
    }

    // ==================== 私有方法 ====================

    /**
     * 构建标题路径字符串
     * 例如：项目概述 > 背景 > 市场环境
     */
    private String buildHeadingPath(List<int[]> levels, List<String> texts, int currentLevel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.size(); i++) {
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(texts.get(i));
        }
        return sb.toString();
    }

    /**
     * 将一个 section 的内容加入分片列表
     * 如果内容超过 MAX_CHUNK_SIZE，自动按段落/句子/硬切再分
     */
    private void addChunks(List<DocumentChunkVO> chunks, String heading, String content, String chunkType) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (trimmed.length() <= MAX_CHUNK_SIZE) {
            chunks.add(buildChunk(heading, trimmed, chunkType));
        } else {
            // 三级降级切分
            List<String> parts = splitByParagraphs(trimmed);
            for (String part : parts) {
                if (part.length() <= MAX_CHUNK_SIZE) {
                    chunks.add(buildChunk(heading, part, chunkType));
                } else {
                    List<String> sentences = splitBySentences(part);
                    for (String sentence : sentences) {
                        if (sentence.length() <= MAX_CHUNK_SIZE) {
                            chunks.add(buildChunk(heading, sentence, chunkType));
                        } else {
                            // 兜底：硬切
                            for (String hard : forceSplit(sentence, HARD_SPLIT_SIZE)) {
                                chunks.add(buildChunk(heading, hard, chunkType));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 按段落边界切分（空行分割）
     * 贪心合并：尽量让每个分片接近 MAX_CHUNK_SIZE 但不超过
     */
    private List<String> splitByParagraphs(String content) {
        String[] paragraphs = content.split("\n\\s*\n");
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (current.length() > 0
                    && current.length() + trimmed.length() + 2 > MAX_CHUNK_SIZE) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;
    }

    /**
     * 按句子边界切分（。！？.!?）
     * 贪心合并：尽量让每个分片接近 MAX_CHUNK_SIZE 但不超过
     */
    private List<String> splitBySentences(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        Matcher matcher = SENTENCE_END.matcher(content);
        int lastEnd = 0;

        while (matcher.find()) {
            int end = matcher.end();
            String sentence = content.substring(lastEnd, end);
            lastEnd = end;

            if (current.length() + sentence.length() > MAX_CHUNK_SIZE && current.length() > 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(sentence);
        }

        // 剩余内容
        if (lastEnd < content.length()) {
            current.append(content.substring(lastEnd));
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;
    }

    /**
     * 硬切：按固定长度切分，兜底策略
     */
    private List<String> forceSplit(String content, int maxSize) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < content.length(); i += maxSize) {
            result.add(content.substring(i, Math.min(i + maxSize, content.length())));
        }
        return result;
    }

    /**
     * 构建 DocumentChunkVO
     */
    private DocumentChunkVO buildChunk(String heading, String content, String chunkType) {
        return DocumentChunkVO.builder()
                .heading(heading)
                .content(content)
                .charCount(content.length())
                .tokenCount(estimateTokens(content))
                .chunkType(chunkType)
                .build();
    }

    /**
     * 预估 Token 数
     * 经验值：中文约 1 字 ≈ 1.5 token，英文约 4 字符 ≈ 1 token
     * 这里简化为 charCount / 1.5，向上取整
     */
    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 1.5);
    }

    /**
     * 读取 InputStream 为字符串
     */
    private String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) {
                    sb.append("\n");
                }
                sb.append(line);
                first = false;
            }
        }
        return sb.toString();
    }
}
