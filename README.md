# TLVMapping Library

A Java library that simplifies working with BER-TLV (Tag-Length-Value) structures by mapping them to plain Java objects using annotations and compile-time code generation.

## Features

- **Annotation-driven** - Annotate your POJOs with `@TLV` and `@Tag` to define the TLV structure
- **Compile-time code generation** - Codecs are generated at build time via annotation processing, so there is no reflection at runtime
- **BER-TLV compliant** - Supports multi-byte tags and lengths following BER encoding rules
- **Nested structures** - Full support for TLV objects nested inside other TLV objects
- **List support** - Encode and decode lists of values or objects with `@TagList`
- **Custom codecs** - Plug in your own encoding/decoding logic for any type
- **Built-in codecs** - `String`, `Integer`, and `Boolean` are supported out of the box

## Requirements

- Java 21+
- Gradle (or any build tool that supports annotation processing)

## Installation

Add the dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'io.github.pedrosilvabk:tlvparsing-library:1.0.0'
    annotationProcessor 'io.github.pedrosilvabk:tlvparsing-library:1.0.0'
}
```

## Quick Start

### 1. Define your model

Annotate a class with `@TLV` and its fields with `@Tag`. The class must have an all-args constructor and getters for every annotated field.

```java
import io.github.pedrosilvabk.annotation.TLV;
import io.github.pedrosilvabk.annotation.Tag;

@TLV(0x6F)
public class CardProfile {
    @Tag(0x50)
    private final String applicationLabel;

    @Tag(0x5A)
    private final String accountNumber;

    @Tag(0x9F08)
    private final Integer versionNumber;

    public CardProfile(String applicationLabel, String accountNumber, Integer versionNumber) {
        this.applicationLabel = applicationLabel;
        this.accountNumber = accountNumber;
        this.versionNumber = versionNumber;
    }

    public String getApplicationLabel() { return applicationLabel; }
    public String getAccountNumber() { return accountNumber; }
    public Integer getVersionNumber() { return versionNumber; }
}
```

### 2. Build the project

Run your build (e.g. `gradle build`). The annotation processor generates:

- `CardProfile__TLVCodec` - an `ObjectTLVCodec<CardProfile>` implementation
- `TLVFactory` - a factory class that registers all generated codecs

### 3. Encode and decode

```java
// Create a TLV instance using the generated factory
TLV tlv = TLVFactory.create();

// Encode an object to BER-TLV bytes
CardProfile profile = new CardProfile("VISA", "1234567890123456", 2);
byte[] encoded = tlv.getBuilder().parse(profile);

// Decode BER-TLV bytes back to an object
CardProfile decoded = tlv.getParser().parse(encoded, CardProfile.class);
```

## Annotations Reference

### `@TLV(int value)`

Applied to a **class**. Declares it as a TLV structure and assigns the parent tag.

```java
@TLV(0x6F)
public class MyStructure { ... }
```

### `@Tag`

Applied to a **field**. Maps it to a tag inside the parent TLV.

| Attribute            | Type      | Default   | Description                                                                 |
|----------------------|-----------|-----------|-----------------------------------------------------------------------------|
| `value`              | `int`     | `-1`      | The tag number for this field (e.g. `0x50`)                                 |
| `useInnerTagAsParent`| `boolean` | `false`   | Delegates to the inner object's own `@TLV` tag instead of wrapping it       |
| `presenceIsValue`    | `boolean` | `false`   | For `boolean` fields only - presence of the tag means `true`, absence `false` |
| `codec`              | `Class`   | `void`    | A custom `ObjectTLVCodec` class to use for encoding/decoding this field     |

### `@TagList`

Applied to a **field** of type `List<T>`. Encodes a list of items inside a container tag.

| Attribute      | Type    | Description                                         |
|----------------|---------|-----------------------------------------------------|
| `containerTag` | `int`   | The tag that wraps the entire list                   |
| `itemTag`      | `int`   | The tag applied to each individual item in the list  |
| `codec`        | `Class` | Optional custom codec for encoding/decoding items    |

## Examples

### Nested TLV Structures

Use `@Tag(useInnerTagAsParent = true)` when the nested object should contribute its own tag directly, without an extra wrapping tag.

```java
@TLV(0x70)
public class IssuerData {
    @Tag(0x9F12)
    private final String issuerName;

    public IssuerData(String issuerName) {
        this.issuerName = issuerName;
    }

    public String getIssuerName() { return issuerName; }
}

@TLV(0x6F)
public class CardData {
    @Tag(0x50)
    private final String label;

    @Tag(useInnerTagAsParent = true)
    private final IssuerData issuerData;

    public CardData(String label, IssuerData issuerData) {
        this.label = label;
        this.issuerData = issuerData;
    }

    public String getLabel() { return label; }
    public IssuerData getIssuerData() { return issuerData; }
}
```

### Boolean Presence Fields

Some TLV protocols represent boolean flags by the presence or absence of a tag (with zero-length value). Use `presenceIsValue = true`:

```java
@TLV(0xA5)
public class ApplicationConfig {
    @Tag(0x50)
    private final String appName;

    @Tag(value = 0x87, presenceIsValue = true)
    private final boolean priorityIndicatorPresent;

    public ApplicationConfig(String appName, boolean priorityIndicatorPresent) {
        this.appName = appName;
        this.priorityIndicatorPresent = priorityIndicatorPresent;
    }

    public String getAppName() { return appName; }
    public boolean getPriorityIndicatorPresent() { return priorityIndicatorPresent; }
}
```

When `priorityIndicatorPresent` is `true`, the encoder outputs `0x87 0x00` (tag with zero length). When `false`, nothing is emitted for that field.

### Lists

Use `@TagList` for repeating elements:

```java
@TLV(0xE1)
public class DirectoryEntry {
    @Tag(0x50)
    private final String appLabel;

    @TagList(containerTag = 0x61, itemTag = 0x4F)
    private final List<String> aidList;

    public DirectoryEntry(String appLabel, List<String> aidList) {
        this.appLabel = appLabel;
        this.aidList = aidList;
    }

    public String getAppLabel() { return appLabel; }
    public List<String> getAidList() { return aidList; }
}
```

The encoded output nests each item under `itemTag` (0x4F), all wrapped inside `containerTag` (0x61).

### Custom Codecs

Implement `ValueTLVCodec<T>` for simple types or `ObjectTLVCodec<T>` for complex types, then reference it in the annotation:

```java
public class HexStringCodec implements ValueTLVCodec<String> {
    @Override
    public Class<String> type() { return String.class; }

    @Override
    public byte[] encode(String value) {
        // Convert hex string like "A0000000031010" to bytes
        int len = value.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(value.charAt(i), 16) << 4)
                    + Character.digit(value.charAt(i + 1), 16));
        }
        return data;
    }

    @Override
    public String decode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}

@TLV(0x61)
public class AidEntry {
    @Tag(value = 0x4F, codec = HexStringCodec.class)
    private final String aid;

    public AidEntry(String aid) { this.aid = aid; }
    public String getAid() { return aid; }
}
```

Register the custom codec before use:

```java
CodecRegistry registry = TLVFactory.createCodecRegistry();
registry.registerCustomCodec(new HexStringCodec());
TLV tlv = TLVFactory.create(registry);
```

### Using the Builder Pattern

`TLVFactory` also supports a builder pattern for more control:

```java
TLV tlv = TLVFactory.builder()
        .tlvParser(customParser)
        .tlvBuilder(customBuilder)
        .build();
```

## Built-in Codecs

| Type      | Encoding                          |
|-----------|-----------------------------------|
| `String`  | UTF-8 byte encoding               |
| `Integer` | 4-byte big-endian                 |
| `Boolean` | `0x01` for true, `0x00` for false |

## Architecture

```
Annotated POJOs
      |
      v
TLVProcessor (compile-time annotation processing)
      |
      +---> *__TLVCodec classes (ObjectTLVCodec implementations)
      +---> TLVFactory (entry point, registers all codecs)
               |
               v
         TLV instance
          /        \
    TLVParser    TLVBuilder
     (decode)     (encode)
```
