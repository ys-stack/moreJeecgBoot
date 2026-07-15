package org.jeecg.modules.airag.practice.cache.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/*
 * @Author: ys
 * @Date: 2026/7/15 16:47
 * @DESC: 缓存工具类
 */
public final class FloatVectorCodec {

    private FloatVectorCodec() {
    }

    public static byte[] encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }

        ByteBuffer buffer = ByteBuffer
                .allocate(vector.length * Float.BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("向量包含 NaN 或无穷大");
            }
            buffer.putFloat(value);
        }

        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("非法向量二进制长度: " + bytes.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];

        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }

        return vector;
    }

    public static float[] copy(float[] vector) {
        return vector == null ? null : vector.clone();
    }

    public static void validate(float[] vector, int expectedDimensions) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding API 返回空向量");
        }

        if (expectedDimensions > 0 && vector.length != expectedDimensions) {
            throw new IllegalArgumentException(
                    "Embedding 向量维度错误，期望="
                            + expectedDimensions
                            + "，实际="
                            + vector.length
            );
        }

        boolean nonZero = false;

        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding 向量包含 NaN 或无穷大");
            }

            if (value != 0.0f) {
                nonZero = true;
            }
        }

        if (!nonZero) {
            throw new IllegalArgumentException("Embedding API 返回全零向量");
        }
    }
}