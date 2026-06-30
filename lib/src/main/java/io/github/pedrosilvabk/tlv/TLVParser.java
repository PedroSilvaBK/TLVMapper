package io.github.pedrosilvabk.tlv;


import io.github.pedrosilvabk.registry.*;
import io.github.pedrosilvabk.utils.TLVUtils;

public class TLVParser {
    private final CodecRegistry codecRegistry;

    public TLVParser(CodecRegistry codecRegistry) {
        this.codecRegistry = codecRegistry;
    }

    public <T> T parse(byte[] tlv, Class<T> targetClass) {
        if (codecRegistry.hasCustomCodec(targetClass)) {
            return decodeWith(codecRegistry.getCustomCodec(targetClass), tlv);
        }
        else {
            return decodeWith(codecRegistry.getGeneratedTLVCodec(targetClass), tlv);
        }
    }

    private <T> T decodeWith(GeneratedCodec<T> codec, byte[] tlv) {
        int[] tagResult = TLVUtils.decodeTag(tlv, 0);
        int[] lengthResult = TLVUtils.decodeLength(tlv, tagResult[1]);
        return codec.decode(tlv, lengthResult[1], lengthResult[0]);
    }

    private <T> T decodeWith(CustomCodec<T> codec, byte[] tlv) {
        return codec.decode(tlv);
    }
}
