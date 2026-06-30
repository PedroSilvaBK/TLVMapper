package io.github.pedrosilvabk.codec;

import io.github.pedrosilvabk.annotation.Codec;
import io.github.pedrosilvabk.registry.CustomCodec;

import java.nio.charset.StandardCharsets;

@Codec
public class StringCodec extends CustomCodec<String> {

    public StringCodec() {
    }

    @Override
    public byte[] encode(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
