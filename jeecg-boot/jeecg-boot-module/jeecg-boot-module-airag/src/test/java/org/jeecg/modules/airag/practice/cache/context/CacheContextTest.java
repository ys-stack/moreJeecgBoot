package org.jeecg.modules.airag.practice.cache.context;

import org.jeecg.modules.airag.practice.vector.cache.EmbeddingCacheContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheContextTest {

    @Test
    void shouldDefaultMissingTenant() {
        assertEquals("0", EmbeddingCacheContext.tenant(null).tenantId());
        assertEquals("0", EmbeddingCacheContext.tenant(" ").tenantId());
    }

    @Test
    void shouldSortAndDeduplicateKnowledgeBases() {
        RagAnswerCacheContext context = new RagAnswerCacheContext(
                "tenant-1",
                List.of("kb-2", "kb-1", "kb-2"),
                "version",
                "model",
                "prompt"
        );
        assertEquals(List.of("kb-1", "kb-2"), context.knowledgeBaseIds());
    }
}
