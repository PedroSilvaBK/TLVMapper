package io.github.pedrosilvabk.processor;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;
import io.github.pedrosilvabk.annotation.Codec;
import io.github.pedrosilvabk.annotation.TLV;
import io.github.pedrosilvabk.annotation.Tag;
import io.github.pedrosilvabk.annotation.TagList;
import io.github.pedrosilvabk.codec.*;
import io.github.pedrosilvabk.registry.*;
import io.github.pedrosilvabk.tlv.TLVBuilder;
import io.github.pedrosilvabk.tlv.TLVParser;
import io.github.pedrosilvabk.utils.TLVUtils;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class TLVProcessor extends AbstractProcessor {
    private static final String GENERATED_PKG = "tlv.generated";
    private static final String CODECS_PKG = GENERATED_PKG + ".codecs";

    private static final String SERVICE_FILE =
            "META-INF/services/io.github.pedrosilvabk.registry.BaseCodec";

    private final List<String> generatedCodecFqcns = new ArrayList<>();
    // ── Data ─────────────────────────────────────────────────────────────

    private static class FieldInfo {
        String name;
        String type;
        TypeMirror typeMirror;
        int tagId;
        boolean customCodec;
        String codecClassName;
        boolean useInnerTagAsParent;
        boolean asNoInnerTag;
        boolean presenceIsValue;
        boolean isList;
        boolean isArray;
        int itemTag;
        String itemType;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(TLV.class.getCanonicalName());
    }

    // ── Entry point ──────────────────────────────────────────────────────

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
//        List<TypeSpec> codecs = new ArrayList<>();
        Element lastElement = null;

        for (Element element : roundEnv.getElementsAnnotatedWith(TLV.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                error("@TLV can only be applied to classes", element);
                continue;
            }

            lastElement = element;
            try {
//                codecs.add(generateCodec((TypeElement) element));
                generateCodec((TypeElement) element);
            } catch (Exception ex) {
                error("Failed generating codec: " + ex, element);
            }
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Codec.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                error("@Codec can only be applied to classes", element);
                continue;
            }

            TypeElement typeElement = (TypeElement) element;

            String t = typeElement.getQualifiedName().toString();

            generatedCodecFqcns.add(t);
        }

        if (roundEnv.processingOver() && !generatedCodecFqcns.isEmpty()) {
            try {
                writeServiceFile();
            } catch (IOException e) {
                error("Failed writing service file: " + e, null);
            }
        }

//        if (lastElement != null) {
//            try {
//                generateTLVFactory(codecs, lastElement);
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        }

        return true;
    }

    private void writeServiceFile() throws IOException {
        FileObject file = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
        try (Writer w = file.openWriter()) {
            for (String fqcn : generatedCodecFqcns) {
                w.write(fqcn);
                w.write('\n');
            }
        }
    }

    // ── Codec generation (orchestrator) ──────────────────────────────────

    private void generateCodec(TypeElement clazz) throws IOException {
        String pkg = CODECS_PKG;
        String simpleName = clazz.getSimpleName().toString();
        int parentTag = clazz.getAnnotation(TLV.class).value();

        String pk2 = packageOf(clazz);


        ClassName className = ClassName.get(pk2, simpleName);
        ClassName genClassName = ClassName.get(pkg, simpleName + "__TLVCodec");

        List<FieldInfo> fields = collectFields(clazz);
        List<MethodSpec> fieldEncoders = fields.stream().map(this::buildFieldEncoder).toList();

        MethodSpec encodeMethod = buildEncodeMethod(className, fields, parentTag);
        MethodSpec decodeMethod = buildDecodeMethod(className, simpleName, fields);

        TypeSpec codec = TypeSpec.classBuilder(genClassName)
                .superclass(ParameterizedTypeName.get(ClassName.get(GeneratedCodec.class), className))
                .addAnnotation(Codec.class)
                .addModifiers(Modifier.PUBLIC)
                // public no-arg constructor required by ServiceLoader
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .build())
                .addMethod(encodeMethod)
                .addMethod(decodeMethod)
                .addMethods(fieldEncoders)
                .build();

        JavaFile.builder(pkg, codec).build().writeTo(processingEnv.getFiler());
    }

    // ── Field collection ─────────────────────────────────────────────────

    private List<FieldInfo> collectFields(TypeElement clazz) {
        List<FieldInfo> fields = new ArrayList<>();

        for (Element enclosed : clazz.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;

            var field = (VariableElement) enclosed;
            Tag tag = field.getAnnotation(Tag.class);
            TagList tagList = field.getAnnotation(TagList.class);
            if (tag == null && tagList == null) continue;

            FieldInfo info = (tagList != null)
                    ? buildListFieldInfo(field, tagList)
                    : buildTagFieldInfo(field, tag);

            fields.add(info);
        }

        return fields;
    }

    private final Map<String, CustomCodec<?>> builtInCodecs = Map.of(
            int.class.getName(), new IntegerCodec(),
            Integer.class.getName(), new IntegerCodec(),
            boolean.class.getName(), new BooleanCodec(),
            String.class.getName(), new StringCodec(),
            byte[].class.getCanonicalName(), new ByteArrCodec(),
            byte.class.getName(), new ByteCodec()
    );

    private FieldInfo buildTagFieldInfo(VariableElement field, Tag tag) {
        FieldInfo info = baseFieldInfo(field);
        info.useInnerTagAsParent = tag.useInnerTagAsParent();
        info.presenceIsValue = tag.presenceIsValue();

        if (info.presenceIsValue && !info.type.equals("boolean") && !info.type.equals("java.lang.Boolean")) {
            error("presenceIsValue can only be used on boolean fields", field);
        }

        TypeMirror codecMirror = resolveCodecMirror(() -> tag.codec());
        CustomCodec<?> codec = builtInCodecs.get( info.type );

        if (isCustomCodec(codecMirror)) {
            info.customCodec = true;
            info.codecClassName = codecMirror.toString();
        }else if ( codec != null) {
            info.customCodec = true;
            info.codecClassName = codec.getClass().getCanonicalName();
        } else {
            info.codecClassName = ClassName.get(field.asType()).toString();
        }

        info.tagId = tag.value();

        TypeElement fieldTypeElement = (TypeElement) processingEnv.getTypeUtils().asElement(field.asType());
        if (fieldTypeElement == null) {
            return info;
        }

        TLV tlv = fieldTypeElement.getAnnotation(TLV.class);
        if (tlv == null) {
            return info;
        }

        if (info.useInnerTagAsParent && tlv.value() == -1) {
            error("Cannot use useInnerTagAsParent when no inner tag is set", field);
        }

        if (tlv.value() == -1) {
            info.asNoInnerTag = true;
            info.tagId = tag.value();
        }
        else if (tag.useInnerTagAsParent()) {
            info.tagId = tlv.value();
        }
        else {
            info.tagId = tag.value();
        }

        return info;
    }

    private FieldInfo buildListFieldInfo(VariableElement field, TagList tagList) {
        FieldInfo info = baseFieldInfo(field);
        info.isList = true;
        info.tagId = tagList.containerTag();
        info.itemTag = tagList.itemTag();
        info.isArray = info.type.contains("[]");

        if (field.asType() instanceof DeclaredType dt && !dt.getTypeArguments().isEmpty()) {
            TypeMirror itemMirror = dt.getTypeArguments().getFirst();
            info.itemType = itemMirror.toString();
        }

        TypeMirror codecMirror = resolveCodecMirror(tagList::codec);
        CustomCodec<?> codec = builtInCodecs.get( info.itemType );

        if (isCustomCodec(codecMirror)) {
            info.customCodec = true;
            info.codecClassName = codecMirror.toString();
        }else if ( codec != null) {
            info.customCodec = true;
            info.codecClassName = codec.getClass().getCanonicalName();
        } else {
            info.codecClassName = ClassName.get(field.asType()).toString();
        }


        return info;
    }

    private FieldInfo baseFieldInfo(VariableElement field) {
        FieldInfo info = new FieldInfo();
        info.name = field.getSimpleName().toString();
        info.type = TypeName.get(field.asType()).withoutAnnotations().toString();
        info.typeMirror = field.asType();
        return info;
    }

    // ── Encode generation ────────────────────────────────────────────────

    private MethodSpec buildEncodeMethod(ClassName className, List<FieldInfo> fields, int parentTag) {
        boolean hasOuterTag = parentTag != -1;

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("encode")
                .addModifiers(Modifier.PUBLIC)
                .returns(byte[].class)
                .addParameter(className, "self");

        String encodeMethodTemplate = "byte[] %s = self.%s == %s ? new byte[0] : encode%s(self.%s)";

        for (FieldInfo fi : fields) {
            if (fi.type.equals("boolean") || fi.type.equals("byte")) {
                methodBuilder.addStatement(
                        "byte[] %s = encode%s(self.%s)"
                                .formatted(fi.name, fi.name.toUpperCase(), getterName( fi.name, fi.type ))
                );

                continue;
            }

            String encodeMethod = encodeMethodTemplate
                    .formatted(fi.name,
                            getterName(fi.name, fi.type),
                            getDefaultValue(fi),
                            fi.name.toUpperCase(),
                            getterName(fi.name, fi.type));

            methodBuilder.addStatement(encodeMethod);
        }

        String concatArgs = String.join(",", fields.stream().map(fi -> fi.name).toList());

        if (!hasOuterTag) {
            methodBuilder.addStatement("return $T.concat(" + concatArgs + ")", TLVUtils.class);
            return methodBuilder.build();
        }

        String lengthArgs = String.join("+", fields.stream().map(fi -> fi.name + ".length").toList());
        methodBuilder.addStatement(writeEncodeTagAs(parentTag, "tag"));
        methodBuilder.addStatement("byte[] length = $T.encodeLength(" + lengthArgs + ")", TLVUtils.class);
        methodBuilder.addStatement(
                "return $T.concat(tag, length, " + concatArgs + ")", TLVUtils.class
        );
        return methodBuilder.build();
    }

    private MethodSpec buildFieldEncoder(FieldInfo fi) {
        if (fi.presenceIsValue) return buildPresenceEncoder(fi);
        if (fi.isList) return buildListEncoder(fi);
        if (fi.useInnerTagAsParent) return buildDelegatingEncoder(fi);
        return buildWrappedEncoder(fi);
    }

    /**
     * Encoder for a boolean field with presenceIsValue=true.
     * Emits tag + zero-length if true, empty byte[] if false.
     */
    private MethodSpec buildPresenceEncoder(FieldInfo fi) {
        return MethodSpec.methodBuilder("encode" + fi.name.toUpperCase())
                .addModifiers(Modifier.PRIVATE)
                .returns(byte[].class)
                .addParameter(ClassName.get(fi.typeMirror), "self")
                .addStatement("if (!self) return new byte[0]")
                .addStatement(writeEncodeTagAs(fi.tagId, "tag"))
                .addStatement("return $T.concat(tag, new byte[] {0x00})", TLVUtils.class)
                .build();
    }

    /**
     * Encoder for a field with an explicit @Tag value.
     * Wraps the encoded value with the field's own tag and length.
     */
    private MethodSpec buildWrappedEncoder(FieldInfo fi) {
        return MethodSpec.methodBuilder("encode" + fi.name.toUpperCase())
                .addModifiers(Modifier.PRIVATE)
                .returns(byte[].class)
                .addParameter(ClassName.get(fi.typeMirror), "self")
                .addStatement(writeEncodeTagAs(fi.tagId, "tag"))
                .addStatement("byte[] value = " + encodeCall(fi, "self"))
                .addStatement("byte[] length = $T.encodeLength(value.length)", TLVUtils.class)
                .addStatement("return $T.concat(tag, length, value)", TLVUtils.class)
                .build();
    }

    /**
     * Encoder for a field with useInnerTagAsParent=true.
     * Delegates entirely to the inner type's codec (which adds its own tag).
     */
    private MethodSpec buildDelegatingEncoder(FieldInfo fi) {
        return MethodSpec.methodBuilder("encode" + fi.name.toUpperCase())
                .addModifiers(Modifier.PRIVATE)
                .returns(byte[].class)
                .addParameter(ClassName.get(fi.typeMirror), "self")
                .addStatement("return " + encodeCall(fi, "self"))
                .build();
    }

    /**
     * Encoder for a @TagList field.
     * Wraps all items inside a container tag, each item with its own item tag.
     */
    private MethodSpec buildListEncoder(FieldInfo fi) {
        String itemAccessMethod = fi.isArray ? "self[i]" : "self.get(i)";
        String sizeMethod = fi.isArray ? "length" : "size()";

        String itemEncode = itemEncodeCall(fi, itemAccessMethod);


        return MethodSpec.methodBuilder("encode" + fi.name.toUpperCase())
                .addModifiers(Modifier.PRIVATE)
                .returns(byte[].class)
                .addParameter(ClassName.get(fi.typeMirror), "self")
                .addStatement(writeEncodeTagAs(fi.tagId, "tag"))
                .addStatement(writeEncodeTagAs(fi.itemTag, "itemTagBytes"))
                .addStatement("byte[][] items = new byte[self.%s][]".formatted(sizeMethod))
                .addCode("""
                for (int i = 0; i < self.%s; i++) {
                  byte[] itemValue = %s;
                  byte[] itemLength = TLVUtils.encodeLength(itemValue.length);
                  items[i] = TLVUtils.concat(itemTagBytes, itemLength, itemValue);
                }
                       \s""".formatted(sizeMethod, itemEncode))
                .addStatement("byte[] content = $T.concat(items)", TLVUtils.class)
                .addStatement("byte[] length = $T.encodeLength(content.length)", TLVUtils.class)
                .addStatement("return $T.concat(tag, length, content)", TLVUtils.class)
                .build();
    }

    // ── Decode generation ────────────────────────────────────────────────

    private MethodSpec buildDecodeMethod(ClassName className, String simpleName, List<FieldInfo> fields) {
        StringBuilder body = new StringBuilder();
        body.append("int end = offset + length;\n");

        appendLocalVariableDeclarations(body, fields);
        appendDuplicateTagCounters(body, fields);
        appendDecodingLoop(body, fields);

        body.append("return new ").append(simpleName).append("(")
                .append(String.join(", ", fields.stream().map(fi -> fi.name).toList()))
                .append(")");


        return MethodSpec.methodBuilder("decode")
                .addModifiers(Modifier.PUBLIC)
                .returns(className)
                .addParameter(byte[].class, "byteArr")
                .addParameter(int.class, "offset")
                .addParameter(int.class, "length")
                .addStatement(body.toString())
                .build();
    }

    private void appendLocalVariableDeclarations(StringBuilder body, List<FieldInfo> fields) {
        for (FieldInfo fi : fields) {
            if (fi.presenceIsValue) {
                body.append("boolean ").append(fi.name).append(" = false;\n");
            } else {
                String declType = fi.isList ? "java.util.List" : boxType(fi.type);
                body.append(declType).append(" ")
                        .append(fi.name)
                        .append(" = ")
                        .append(getDefaultValue(fi))
                        .append(";\n");
            }
        }
    }

    private String getDefaultValue(FieldInfo fi) {
        if (fi.type.equals("int")) {
            return String.valueOf(Integer.MIN_VALUE);
        }
        else if  (fi.type.equals("long")) {
            return "(long)" + Integer.MIN_VALUE;
        }
        else {
            return "null";
        }
    }

    private void appendDuplicateTagCounters(StringBuilder body, List<FieldInfo> fields) {
        for (var entry : groupByTag(fields).entrySet()) {
            if (entry.getValue().size() > 1) {
                body.append("int tagCount_").append(tagHex(entry.getKey())).append(" = 0;\n");
            }
        }
    }

    private void appendDecodingLoop(StringBuilder body, List<FieldInfo> fields) {
        body.append("""
                while (offset < end) {
                int[] tagResult = TLVUtils.decodeTag(byteArr, offset);
                int tagValue = tagResult[0];
                offset = tagResult[1];
                int[] lengthResult = TLVUtils.decodeLength(byteArr, offset);
                int fieldLength = lengthResult[0];
                offset = lengthResult[1];
                switch (tagValue) {
                """);

        for (var entry : groupByTag(fields).entrySet()) {
            List<FieldInfo> group = entry.getValue();
            body.append("case ").append(tagHex(entry.getKey())).append(": {\n");

            if (group.size() == 1) {
                appendDecodeForField(body, group.getFirst());
            } else {
                appendDuplicateTagDecode(body, entry.getKey(), group);
            }

            if (!group.getFirst().isList) {
                body.append("offset += fieldLength;\n");
            }
            body.append("break;\n}\n");
        }

        body.append("default: { offset += fieldLength; break; }\n");
        body.append("}\n"); // close switch
        body.append("}\n"); // close while
    }

    private void appendDuplicateTagDecode(StringBuilder body, int tagId, List<FieldInfo> group) {
        String counterName = "tagCount_" + tagHex(tagId);
        for (int i = 0; i < group.size(); i++) {
            String cond = (i == 0) ? "if" : "} else if";
            body.append(cond).append(" (").append(counterName).append(" == ").append(i).append(") {\n");
            appendDecodeForField(body, group.get(i));
        }
        body.append("}\n");
        body.append(counterName).append("++;\n");
    }

    private void appendDecodeForField(StringBuilder sb, FieldInfo fi) {
        if (fi.presenceIsValue) {
            sb.append(fi.name).append(" = true;\n");
        } else if (fi.isList) {
            appendListDecode(sb, fi);
        } else if (fi.customCodec) {
            sb.append(fi.name).append(" = (").append(fi.type).append(")")
                    .append("registry.getCustomCodec(").append(fi.codecClassName).append(".class)")
                    .append(".decode(java.util.Arrays.copyOfRange(byteArr, offset, offset + fieldLength));\n");
        } else if (fi.useInnerTagAsParent || fi.asNoInnerTag) {
            sb.append(fi.name).append(" = (").append(fi.type).append(")")
                    .append("registry.getGeneratedTLVCodec(").append(fi.type).append(".class)")
                    .append(".decode(byteArr, offset, fieldLength);\n");
        }
        else {
            // Explicit @Tag wrapping an object — strip inner tag+length first
            sb.append("{\n");
            sb.append("int[] innerTagResult = TLVUtils.decodeTag(byteArr, offset);\n");
            sb.append("int[] innerLenResult = TLVUtils.decodeLength(byteArr, innerTagResult[1]);\n");
            sb.append(fi.name).append(" = (").append(fi.type).append(")")
                    .append("registry.getGeneratedTLVCodec(").append(fi.type).append(".class)")
                    .append(".decode(byteArr, innerLenResult[1], innerLenResult[0]);\n");
            sb.append("}\n");
        }
    }

    private void appendListDecode(StringBuilder sb, FieldInfo fi) {
        sb.append(fi.name).append(" = new java.util.ArrayList<>();\n");
        sb.append("int listEnd = offset + fieldLength;\n");
        sb.append("while (offset < listEnd) {\n");
        sb.append("int[] itemTagResult = TLVUtils.decodeTag(byteArr, offset);\n");
        sb.append("offset = itemTagResult[1];\n");
        sb.append("int[] itemLengthResult = TLVUtils.decodeLength(byteArr, offset);\n");
        sb.append("int itemLen = itemLengthResult[0];\n");
        sb.append("offset = itemLengthResult[1];\n");

        if (fi.customCodec) {
            sb.append(fi.name).append(".add(registry.getCustomCodec(")
                    .append(fi.codecClassName).append(".class).decode(java.util.Arrays.copyOfRange(byteArr, offset, offset + fieldLength)));\n");
        } else {
            sb.append(fi.name).append(".add(registry.getGeneratedTLVCodec(")
                    .append(fi.itemType).append(".class).decode(byteArr, offset, itemLen));\n");
        }

        sb.append("offset += itemLen;\n");
        sb.append("}\n");
    }

    // ── Factory generation ───────────────────────────────────────────────

    private void generateTLVFactory(List<TypeSpec> codecs, Element e) throws IOException {
        String pkg = GENERATED_PKG;
        ClassName tlvClass = ClassName.get(io.github.pedrosilvabk.tlv.TLV.class);

        ClassName factoryClass = ClassName.get(pkg, "TLVFactory");


        MethodSpec.Builder registryMethod = MethodSpec.methodBuilder("createCodecRegistry");

        registryMethod.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
        registryMethod.returns(CodecRegistry.class);

        registryMethod.addStatement("CodecRegistry cr = new CodecRegistry();\n");
        registryMethod.addStatement("cr.registerValue(new $T());\n", IntegerCodec.class);
        registryMethod.addStatement("cr.registerValue(new $T());\n", StringCodec.class);
        registryMethod.addStatement("cr.registerValue(new $T());\n", BooleanCodec.class);

        for (TypeSpec codec : codecs) {
            ClassName codecClass = ClassName.get(CODECS_PKG, codec.name);

            registryMethod.addStatement("cr.registerObject(new $T(cr));\n", codecClass);
        }

        registryMethod.addStatement("return cr");

        TypeSpec factory = TypeSpec.classBuilder("TLVFactory")
                .addModifiers(Modifier.PUBLIC)
                .addField(TLVParser.class, "parser", Modifier.PRIVATE)
                .addField(TLVBuilder.class, "builder", Modifier.PRIVATE)
                // Private constructor
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .addParameter(CodecRegistry.class, "codecRegistry")
                        .addStatement("this.parser = new TLVParser(codecRegistry)")
                        .addStatement("this.builder = new TLVBuilder(codecRegistry)")
                        .build())
                // static createCodecRegistry()
                .addMethod( registryMethod.build() )
                // static create() → TLV
                .addMethod(MethodSpec.methodBuilder("create")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(tlvClass)
                        .addStatement("CodecRegistry cr = createCodecRegistry()")
                        .addStatement("return new $T(new TLVParser(cr), new TLVBuilder(cr))", io.github.pedrosilvabk.tlv.TLV.class)
                        .build())
                // static create(CodecRegistry) → TLV
                .addMethod(MethodSpec.methodBuilder("create")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(tlvClass)
                        .addParameter(CodecRegistry.class, "codecRegistry")
                        .addStatement("return new $T(new TLVParser(codecRegistry), new TLVBuilder(codecRegistry))", io.github.pedrosilvabk.tlv.TLV.class)
                        .build())
                // static builder() → TLVFactory
                .addMethod(MethodSpec.methodBuilder("builder")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(factoryClass)
                        .addStatement("return new TLVFactory(createCodecRegistry())")
                        .build())
                // static builder(CodecRegistry) → TLVFactory
                .addMethod(MethodSpec.methodBuilder("builder")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(factoryClass)
                        .addParameter(CodecRegistry.class, "codecRegistry")
                        .addStatement("return new TLVFactory(codecRegistry)")
                        .build())
                // Customization: tlvBuilder(TLVBuilder)
                .addMethod(MethodSpec.methodBuilder("tlvBuilder")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(factoryClass)
                        .addParameter(TLVBuilder.class, "builder")
                        .addStatement("this.builder = builder")
                        .addStatement("return this")
                        .build())
                // Customization: tlvParser(TLVParser)
                .addMethod(MethodSpec.methodBuilder("tlvParser")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(factoryClass)
                        .addParameter(TLVParser.class, "parser")
                        .addStatement("this.parser = parser")
                        .addStatement("return this")
                        .build())
                // build() → TLV
                .addMethod(MethodSpec.methodBuilder("build")
                        .addModifiers(Modifier.PUBLIC)
                        .returns(tlvClass)
                        .addStatement("return new $T(parser, builder)", io.github.pedrosilvabk.tlv.TLV.class)
                        .build())
                .build();

        JavaFile.builder(pkg, factory).build().writeTo(processingEnv.getFiler());
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /** Builds the encode call expression for a regular field. */
    private static String encodeCall(FieldInfo fi, String arg) {
        return registryLookup(fi) + ".encode(" + arg + ")";
    }

    /** Builds the encode call expression for a list item. */
    private static String itemEncodeCall(FieldInfo fi, String arg) {
        return itemRegistryLookup(fi) + ".encode(" + arg + ")";
    }

    private static String registryLookup(FieldInfo fi) {
        if (fi.customCodec) return "registry.getCustomCodec(" + fi.codecClassName + ".class)";
        return "registry.getGeneratedTLVCodec(" + fi.type + ".class)";
    }

    private static String itemRegistryLookup(FieldInfo fi) {
        if (fi.customCodec) return "registry.getCustomCodec(" + fi.codecClassName + ".class)";
        return "registry.getGeneratedTLVCodec(" + fi.itemType + ".class)";
    }

    /** Resolves a codec Class attribute that throws MirroredTypeException. */
    private static TypeMirror resolveCodecMirror(Runnable accessor) {
        try {
            accessor.run();
            return null;
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }

    private static boolean isCustomCodec(TypeMirror codecMirror) {
        return codecMirror != null && !codecMirror.toString().equals("void");
    }

    private boolean isValueType(TypeMirror typeMirror) {
        TypeName typeName = TypeName.get(typeMirror);
        if (typeName.isPrimitive() || typeName.isBoxedPrimitive()) {
            return true;
        }
        if (typeMirror.getKind() == TypeKind.ARRAY) {
            TypeMirror component = ((ArrayType) typeMirror).getComponentType();
            return component.getKind() == TypeKind.BYTE;
        }
        return typeMirror.toString().equals("java.lang.String");
    }

    private static LinkedHashMap<Integer, List<FieldInfo>> groupByTag(List<FieldInfo> fields) {
        LinkedHashMap<Integer, List<FieldInfo>> groups = new LinkedHashMap<>();
        for (FieldInfo fi : fields) {
            groups.computeIfAbsent(fi.tagId, k -> new ArrayList<>()).add(fi);
        }
        return groups;
    }

    private static String tagHex(int tag) {
        return "0x" + String.format("%02X", tag);
    }

    private static String boxType(String type) {
        return switch (type) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "boolean" -> "Boolean";
            case "byte" -> "Byte";
            case "short" -> "Short";
            case "float" -> "Float";
            case "double" -> "Double";
            case "char" -> "Character";
            default -> type;
        };
    }

    private static String getterName(String fieldName, String type) {
        String suffix =fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1) + "()";

        if (type.equals("boolean")) {
            return "is" +  suffix;
        }

        return "get" + suffix;
    }

    private String packageOf(Element element) {
        return processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private String writeEncodeTagAs(int tag, String varName) {
        byte[] encodedTag = TLVUtils.encodeTag(tag);
        StringBuilder sb = new StringBuilder();

        sb.append("byte[] ").append(varName).append(" = new byte[] {");

        for (int i = 0; i < encodedTag.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }

            int unsignedValue = encodedTag[i] & 0xFF;

            if (unsignedValue > 127) {
                sb.append("(byte) ");
            }

            sb.append("0x")
                    .append(String.format("%02X", unsignedValue));
        }

        sb.append("}");

        return sb.toString();
    }
}
