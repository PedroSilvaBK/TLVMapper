package io.github.pedrosilvabk.codec;


import io.github.pedrosilvabk.annotation.Codec;
import io.github.pedrosilvabk.registry.CustomCodec;

@Codec
public class BooleanCodec extends CustomCodec<Boolean> {
    public BooleanCodec() {
    }

    public byte[] encode(Boolean value) {
        return new byte[]{(byte) (value ? 1 : 0)};
    }

    public Boolean decode(byte[] bytes) {
        return bytes.length > 0 && bytes[0] != 0;
    }
}