package io.github.pedrosilvabk.registry;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

public final class CodecRegistry {
    private final Map<String, BaseCodec<?>> codecs = new HashMap<>();

    public <T> void registerCodec(BaseCodec<T> codec) {
        if (codec instanceof GeneratedCodec<T>) {
            Class<?> clazz = handledType(codec);
            assert clazz != null;
            codecs.put(clazz.getName(), codec);
        }
        else {
            codecs.put(codec.getClass().getName(), codec);
        }
    }

    @SuppressWarnings("unchecked")
    public <T, B> GeneratedCodec<T> getGeneratedTLVCodec(Class<B> clazz) {
        BaseCodec codec = codecs.get(clazz.getName());
        if (codec == null) {
            throw new IllegalArgumentException(String.format("codec %s not found", clazz.getName()));
        }

        if (!(codec instanceof GeneratedCodec<?>)) {
            throw new IllegalArgumentException(String.format("%s is not a GeneratedTLVCodec", clazz.getName()));
        }

        return (GeneratedCodec<T>) codec;
    }

    @SuppressWarnings("unchecked")
    public <T, B> CustomCodec<T> getCustomCodec(Class<B> clazz) {
        BaseCodec<T> codec = (BaseCodec<T>) codecs.get(clazz.getName());
        if (codec == null) {
            throw new IllegalArgumentException(String.format("codec %s not found", clazz.getName()));
        }

        if (!(codec instanceof CustomCodec<?>)) {
            throw new IllegalArgumentException(String.format("%s is not a CustomCodec", clazz.getName()));
        }

        return (CustomCodec<T>) codec;
    }

    public <T> BaseCodec<T> getCodec(Class<T> clazz) {
        BaseCodec<T> codec = (BaseCodec<T>) codecs.get(clazz.getName());
        if (codec == null) {
            throw new IllegalArgumentException(String.format("codec %s not found", clazz.getName()));
        }

        return codec;
    }

    public <T> boolean hasCustomCodec(Class<T> clazz) {
        BaseCodec<T> codec = (BaseCodec<T>) codecs.get(clazz.getName());

        return codec instanceof CustomCodec;
    }

    /** The concrete T that a codec handles, taken from BaseCodec<T> up the hierarchy. */
    static Class<?> handledType(BaseCodec<?> codec) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        Type current = codec.getClass();

        while (current != null) {
            Class<?> raw;
            if (current instanceof ParameterizedType pt) {
                raw = (Class<?>) pt.getRawType();

                // record this level's type-variable -> actual-argument bindings
                TypeVariable<?>[] vars = raw.getTypeParameters();
                Type[] args = pt.getActualTypeArguments();
                for (int i = 0; i < vars.length; i++) {
                    bindings.put(vars[i], resolve(args[i], bindings));
                }

                if (raw == BaseCodec.class) {
                    return toClass(resolve(args[0], bindings));
                }
            } else {
                raw = (Class<?>) current;
                if (raw == BaseCodec.class) {
                    return Object.class; // raw use, no type info available
                }
            }
            current = raw.getGenericSuperclass();
        }
        return null;
    }

    private static Type resolve(Type t, Map<TypeVariable<?>, Type> bindings) {
        while (t instanceof TypeVariable) {
            Type next = bindings.get(t);
            if (next == null) break;
            t = next;
        }
        return t;
    }

    private static Class<?> toClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return null;
    }
}