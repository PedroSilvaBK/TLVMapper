package io.github.pedrosilvabk.codec;


import io.github.pedrosilvabk.annotation.Codec;
import io.github.pedrosilvabk.registry.CustomCodec;

@Codec
public class IntegerCodec extends CustomCodec<Integer> {
    public IntegerCodec() {
    }

    @Override
    public byte[] encode(Integer value) {
        byte[] bytes = new byte[4];
        for (int i = 3; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }

        return bytes;
    }

    @Override
    public Integer decode(byte[] bytes) {
        if (bytes.length > 4) {
            throw new IllegalArgumentException("Invalid byte array length");
        }

        int value = 0;

        for (byte aByte : bytes) {
            value = (value << 8) | (aByte & 0xFF);
        }
        return value;
    }
}
