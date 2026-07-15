package org.jeecg.modules.airag.practice.cache.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FloatVectorCodecTest {

    @Test
    void shouldRoundTripFloatVector() {
        float[] source = {1.25f, -2.5f, 0.125f, 9.75f};
        byte[] encoded = FloatVectorCodec.encode(source);
        float[] decoded = FloatVectorCodec.decode(encoded);
        assertArrayEquals(source, decoded);
    }

    @Test
    void shouldRejectAllZeroVector() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FloatVectorCodec.validate(new float[]{0.0f, 0.0f}, 2)
        );
    }

    @Test
    void shouldRejectUnexpectedDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FloatVectorCodec.validate(new float[]{1.0f, 2.0f}, 3)
        );
    }
}
