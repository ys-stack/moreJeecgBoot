package org.jeecg.modules.airag.practice.cache;

import org.jeecg.modules.airag.practice.cache.util.PracticeCachePayloadCipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PracticeCachePayloadCipherTest {

    private final PracticeCachePayloadCipher cipher = new PracticeCachePayloadCipher(
            "0123456789abcdef0123456789abcdef"
    );

    @Test
    void shouldEncryptAndDecryptPayload() {
        String plaintext = "用户问题与知识库答案";
        String encrypted = cipher.encrypt(plaintext);

        assertFalse(encrypted.contains(plaintext));
        assertEquals(plaintext, cipher.decrypt(encrypted));
    }

    @Test
    void shouldRejectTamperedPayload() {
        String encrypted = cipher.encrypt("sensitive-answer");
        int index = encrypted.length() / 2;
        char replacement = encrypted.charAt(index) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, index)
                + replacement
                + encrypted.substring(index + 1);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }
}
