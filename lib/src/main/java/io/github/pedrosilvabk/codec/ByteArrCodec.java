package io.github.pedrosilvabk.codec;

import io.github.pedrosilvabk.annotation.Codec;
import io.github.pedrosilvabk.registry.CustomCodec;

@Codec
public class ByteArrCodec extends CustomCodec<byte[]> {
    public ByteArrCodec() {
    }

    public byte[] encode(byte[] value) {
        return value;
    }

    public byte[] decode(byte[] bytes) {
        return bytes;
    }
}
