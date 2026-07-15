package org.jeecg.modules.airag.practice.cache.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 使用 AES-GCM 加密 Redis 中的 RAG 答案，避免缓存泄露时直接暴露业务数据。 */
@Component
public class PracticeCachePayloadCipher {

    private static final String VERSION_PREFIX = "v1.";
    private static final byte[] KEY_CONTEXT =
            "airag-practice-cache-payload-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD =
            "airag:rag-answer:v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PracticeCachePayloadCipher(
            @Value("${practice.cache.hmac-secret:jeecg-practice-cache-development-secret-change-in-production}")
            String secret) {
        Assert.hasText(secret, "practice.cache.hmac-secret 不能为空");
        if (secret.length() < 32) {
            throw new IllegalArgumentException("practice.cache.hmac-secret 长度不能小于 32 个字符");
        }
        this.encryptionKey = new SecretKeySpec(deriveKey(secret), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("加密 RAG 缓存数据失败", e);
        }
    }

    public String decrypt(String encryptedPayload) {
        if (encryptedPayload == null || !encryptedPayload.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("不支持的 RAG 缓存密文版本");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(
                    encryptedPayload.substring(VERSION_PREFIX.length())
            );
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("RAG 缓存密文长度非法");
            }

            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密 RAG 缓存数据失败", e);
        }
    }

    /** 通过上下文隔离派生独立 AES-256 密钥，避免直接复用 HMAC 原始密钥。 */
    private byte[] deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_CONTEXT);
            digest.update((byte) 0);
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("派生 RAG 缓存加密密钥失败", e);
        }
    }
}
