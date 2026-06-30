package io.github.pedrosilvabk.registry;

public abstract class GeneratedCodec<T> extends BaseCodec<T> {
    public GeneratedCodec() {
    }

    public abstract T decode(byte[] bytes, int offset, int fieldLength);
}
