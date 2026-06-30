package io.github.pedrosilvabk.registry;

public abstract class CustomCodec<T> extends BaseCodec<T> {
    public CustomCodec() {
    }

    public abstract T decode(byte[] bytes);
}
