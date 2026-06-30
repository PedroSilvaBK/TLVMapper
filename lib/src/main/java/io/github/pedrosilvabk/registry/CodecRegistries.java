package io.github.pedrosilvabk.registry;

import io.github.pedrosilvabk.codec.BooleanCodec;
import io.github.pedrosilvabk.codec.ByteArrCodec;
import io.github.pedrosilvabk.codec.IntegerCodec;
import io.github.pedrosilvabk.codec.StringCodec;

import java.util.ServiceLoader;

public final class CodecRegistries {

    private CodecRegistries() {}

    public static CodecRegistry discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    public static CodecRegistry discover(ClassLoader loader) {
        CodecRegistry cr = new CodecRegistry();

        loadDefaultCodecs( cr );

        java.util.List<BaseCodec<?>> codecs = new java.util.ArrayList<>();
        for (BaseCodec<?> codec : ServiceLoader.load(BaseCodec.class, loader)) {
            codecs.add(codec);
        }

        for (BaseCodec<?> codec : codecs) {
            cr.registerCodec(codec);
            codec.setRegistry(cr);
        }
        return cr;
    }

    private static void loadDefaultCodecs(CodecRegistry cr) {
        cr.registerCodec(new IntegerCodec());
        cr.registerCodec(new StringCodec());
        cr.registerCodec(new BooleanCodec());
        cr.registerCodec(new ByteArrCodec());
    }
}
