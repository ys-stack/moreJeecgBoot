package org.jeecg.modules.airag.practice.cache.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PracticeCacheKeyHasherTest {

    private final PracticeCacheKeyHasher hasher = new PracticeCacheKeyHasher(
            "unit-test-cache-secret-with-more-than-32-characters"
    );

    @Test
    void shouldGenerateStableHmac() {
        String first = hasher.hmac("tenant-1", "model-v1", "hello");
        String second = hasher.hmac("tenant-1", "model-v1", "hello");
        assertEquals(first, second);
    }

    @Test
    void shouldIsolateTenantCacheKeys() {
        String first = hasher.hmac("tenant-1", "model-v1", "hello");
        String second = hasher.hmac("tenant-2", "model-v1", "hello");
        assertNotEquals(first, second);
    }

    @Test
    void shouldPreserveQuestionPunctuationSemantics() {
        String cPlusPlus = hasher.normalizeQuestion(" C++ 是什么？ ");
        String cLanguage = hasher.normalizeQuestion("C 是什么？");
        assertNotEquals(cPlusPlus, cLanguage);
    }
}
