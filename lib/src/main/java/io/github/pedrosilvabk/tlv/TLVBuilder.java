package io.github.pedrosilvabk.tlv;


import io.github.pedrosilvabk.registry.BaseCodec;
import io.github.pedrosilvabk.registry.CodecRegistry;
import io.github.pedrosilvabk.registry.GeneratedTLVCodec;
import io.github.pedrosilvabk.registry.ValueTLVCodec;

public class TLVBuilder {
    private final CodecRegistry codecRegistry;

    public TLVBuilder(CodecRegistry codecRegistry) {
        this.codecRegistry = codecRegistry;
    }

    public byte[] parse(Object object) {
        if (object.getClass().isPrimitive()) {
//            assert codecRegistry.getValue(object.getClass()) != null;
//
//            return encodeWith(codecRegistry.getGeneratedTLVCodec(object.getClass()));

            throw new IllegalArgumentException(String.format("%s is primitive", object.getClass().getSimpleName()));
        }
        else {
            return encodeWith(
                    codecRegistry.getCodec( object.getClass() ),
                    object
            );
        }
    }

    private <T> byte[] encodeWith(BaseCodec<T> codec, Object object) {
        return codec.encode((T) object);
    }
}
