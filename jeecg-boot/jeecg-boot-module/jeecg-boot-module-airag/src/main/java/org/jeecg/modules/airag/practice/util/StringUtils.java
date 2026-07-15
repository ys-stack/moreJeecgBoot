package org.jeecg.modules.airag.practice.util;

import org.apache.commons.codec.digest.DigestUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class StringUtils extends org.apache.commons.lang3.StringUtils {
    /**
     * 文本归一化洗涤（去除首尾空格、去除所有标点符号、转小写、合并连续多余空白）
     * 用途：解决字面表述差异小（如多加个问号、大小写不同）导致无法精准命中哈希的问题
     */
    public static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim()
                .toLowerCase()
                // 移除中文和英文的标点符号及特殊符号
                .replaceAll("[\\p{P}\\p{S}]", "")
                // 将所有连续空白字符合并为空格
                .replaceAll("\\s+", "")
                .trim();
    }
    /**
     * 获取归一化文本的 MD5 哈希
     */
    public static String getNormalizedMd5(String text) {
        return DigestUtils.md5Hex(normalizeText(text));
    }
    /**
     * 计算两个 浮点向量（如 1024 维）的余弦相似度（Cosine Similarity）
     *
     * @param vec1 向量 A
     * @param vec2 向量 B
     * @return 相似度范围 [-1.0f, 1.0f]，通常问答相似度为 [0.0f, 1.0f]
     */
    public static float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length || vec1.length == 0) {
            return 0.0f;
        }
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            normA += vec1[i] * vec1[i];
            normB += vec2[i] * vec2[i];
        }
        if (normA == 0.0f || normB == 0.0f) {
            return 0.0f;
        }
        return dotProduct / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }
    /**
     * 将 float[] 转成二进制 byte[]（大端序）
     * 优点：1024维向量在 JSON 里占 10KB 以上，转二进制后固定仅占 4096 字节（4KB），大幅节省 Redis 内存与网络带宽
     */
    public static byte[] floatsToBytes(float[] floats) {
        if (floats == null) return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.BIG_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
    /**
     * 将二进制 byte[] 转回 float[]
     */
    public static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }
}
