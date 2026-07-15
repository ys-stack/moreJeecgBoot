package org.jeecg.modules.airag.practice.cache.util;

import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

/*
 * @Author: ys
 * @Date: 2026/7/15 16:47
 * @DESC: 缓存键
 */
@Component
@Slf4j
public class PracticeCacheKeyHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    private static final String DEVELOPMENT_SECRET =
            "jeecg-practice-cache-development-secret-change-in-production";

    public PracticeCacheKeyHasher(
            @Value("${practice.cache.hmac-secret:jeecg-practice-cache-development-secret-change-in-production}")
            String hmacSecret) {
        Assert.hasText(hmacSecret, "practice.cache.hmac-secret 不能为空");
        if (hmacSecret.length() < 32) {
            throw new IllegalArgumentException(
                    "practice.cache.hmac-secret 长度不能小于 32 个字符");
        }
        if (DEVELOPMENT_SECRET.equals(hmacSecret)) {
            log.warn("当前使用缓存 HMAC 开发密钥，生产环境必须配置 practice.cache.hmac-secret");
        }
        this.secret = hmacSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String hmac(String... parts) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));

            for (String part : parts) {
                String value = part == null ? "" : part;
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                mac.update(intToBytes(bytes.length));
                mac.update(bytes);
            }

            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("生成缓存 HMAC 失败", e);
        }
    }

    public String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("生成 SHA-256 失败", e);
        }
    }

    /**
     * Embedding 输入归一化必须保留标点和空格语义。
     */
    public String normalizeEmbeddingText(String text) {
        if (text == null) {
            return "";
        }

        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
    }

    /**
     * FAQ 精确匹配使用保守归一化，不删除标点。
     */
    public String normalizeQuestion(String question) {
        if (question == null) {
            return "";
        }

        return Normalizer.normalize(question, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
