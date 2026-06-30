package io.github.pedrosilvabk.registry;

public abstract class BaseCodec<T> {
    protected CodecRegistry registry;

    public BaseCodec() {

    }

    public CodecRegistry getRegistry() {
        return registry;
    }

    public void setRegistry(CodecRegistry registry) {
        this.registry = registry;
    }

    public abstract byte[] encode(T object);
}
