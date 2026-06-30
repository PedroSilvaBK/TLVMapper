package io.github.pedrosilvabk.registry;

import io.github.pedrosilvabk.tlv.TLV;
import io.github.pedrosilvabk.tlv.TLVBuilder;
import io.github.pedrosilvabk.tlv.TLVParser;

public class TLVFactory {
    public static CodecRegistry createCodecRegistry() {
        return CodecRegistries.discover();
    }

    public static TLV create() {
        CodecRegistry cr = createCodecRegistry();
        return new TLV(new TLVParser(cr), new TLVBuilder(cr));
    }
}
