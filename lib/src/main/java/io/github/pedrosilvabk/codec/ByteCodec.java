package io.github.pedrosilvabk.codec;

import io.github.pedrosilvabk.annotation.Codec;
import io.github.pedrosilvabk.registry.CustomCodec;

@Codec
public class ByteCodec extends CustomCodec<Byte> {
    @Override
    public Byte decode(byte[] bytes) {
        if (bytes.length != 1) {
            throw new IllegalArgumentException("ByteCodec decode error");
        }

        return bytes[0];
    }

    @Override
    public byte[] encode(Byte object) {
        return new byte[] {object};
    }
}
