package io.github.pedrosilvabk.utils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TLVUtils {
    public static byte[] encodeLength(int arrLength) {
        if (arrLength < 0x80) {
            return new byte[]{(byte) arrLength};
        } else if (arrLength <= 0xFF) {
            return new byte[]{(byte) 0x81, (byte) arrLength};
        } else if (arrLength <= 0xFFFF) {
            return new byte[]{(byte) 0x82, (byte) (arrLength >> 8), (byte) arrLength};
        } else if (arrLength <= 0xFFFFFF) {
            return new byte[]{(byte) 0x83, (byte) (arrLength >> 16), (byte) (arrLength >> 8), (byte) arrLength};
        } else {
            return new byte[]{(byte) 0x84, (byte) (arrLength >> 24), (byte) (arrLength >> 16), (byte) (arrLength >> 8), (byte) arrLength};
        }
    }

    public static int[] decodeTag(byte[] data, int offset) {
        int firstByte = data[offset] & 0xFF;
        if ((firstByte & 0x1F) != 0x1F) {
            return new int[]{firstByte, offset + 1};
        }
        int tag = firstByte;
        do {
            offset++;
            int nextByte = data[offset] & 0xFF;
            tag = (tag << 8) | nextByte;
            if ((nextByte & 0x80) == 0) {
                offset++;
                break;
            }
        } while (true);
        return new int[]{tag, offset};
    }

    public static int[] decodeLength(byte[] data, int offset) {
        int firstByte = data[offset] & 0xFF;
        if (firstByte < 0x80) {
            return new int[]{firstByte, offset + 1};
        }
        int numBytes = firstByte & 0x7F;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (data[offset + 1 + i] & 0xFF);
        }
        return new int[]{length, offset + 1 + numBytes};
    }

    public static byte[] encodeTag(int tag) {
        if (tag < 0) {
            throw new IllegalArgumentException("Tag must be non-negative");
        }
        if (tag <= 0xFF) {
            return new byte[]{(byte) tag};
        } else if (tag <= 0xFFFF) {
            return new byte[]{
                    (byte) ((tag >> 8) & 0xFF),
                    (byte) (tag & 0xFF)
            };
        } else if (tag <= 0xFFFFFF) {
            return new byte[]{
                    (byte) ((tag >> 16) & 0xFF),
                    (byte) ((tag >> 8) & 0xFF),
                    (byte) (tag & 0xFF)
            };
        } else {
            return new byte[]{
                    (byte) ((tag >> 24) & 0xFF),
                    (byte) ((tag >> 16) & 0xFF),
                    (byte) ((tag >> 8) & 0xFF),
                    (byte) (tag & 0xFF)
            };
        }
    }

    public static byte[] concat(byte[]... arrs) {
        int totalLength = 0;
        for (byte[] arr : arrs) {
            totalLength += arr.length;
        }
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] arr : arrs) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }

    public static Map<Integer, byte[]> findFUllTLVs(byte[] tlv, List<Integer> tags) {
        if (tags.isEmpty()) {
            return null;
        }

        Map<Integer, byte[]> result = new HashMap<>();
        for (Integer tag : tags) {
            result.put(tag, null);
        }

        int[] count = {0};

        parseTLV(tlv, 0, tlv.length, result, count);

        return result;
    }

    private static void parseTLV(byte[] tlv, int from, int to, Map<Integer, byte[]> result, int[] count) {
        int index = 0;
        int length = to - from;
        while (index < length) {
            if (count[0] == result.size()) {
                break;
            }

            int[] tlvRead = readOneTLV(Arrays.copyOfRange(tlv, from, to), index);

            int tag = tlvRead[0];
            int valueIndex = tlvRead[2];
            int nextIndex = tlvRead[tlvRead.length - 1];

            if (result.containsKey(tag)) {
                result.put(tag, Arrays.copyOfRange(tlv, from + index, from + nextIndex));
                count[0]++;
            }

            index = nextIndex;

            if (count[0] == result.size()) {
                break;
            }

            int firstTagByte;
            if (tag > 0xFFFF) {
                firstTagByte = (tag >> 16) & 0xFF;
            } else if (tag > 0xFF) {
                firstTagByte = (tag >> 8) & 0xFF;
            } else {
                firstTagByte = tag & 0xFF;
            }

            boolean constructed = (firstTagByte & 0x20) != 0;

            if (constructed) {
                // This needs to happen because this function doesnt rely an known tag registry to work, which means that some times it can
                // keep on recursing without valid results
                // the only way to fix this is by having this class make use of the registry
                try {
                    parseTLV(tlv, from + valueIndex, from + nextIndex, result, count);
                } catch (Exception e) {
                    // Not actually constructed, skip
                }
            }
        }
    }

    public static byte[] buildTlv(int tag, int expectedPayloadLength, byte[] payload) {
        byte[] tagBytes = encodeTag( tag );
        if (expectedPayloadLength != payload.length ) {
            throw new IllegalArgumentException("Payload length does not match expected length");
        }

        byte[] lengthBytes = encodeLength(payload.length);
        return concat(tagBytes, lengthBytes, payload);
    }

    public static byte[] buildTlv(int tag, int payload) {
        byte[] tagBytes = encodeTag(tag);
        if (payload == 0) {
            return concat(tagBytes, encodeLength(payload));
        } else {
            byte[] payloadBytes = new byte[]{(byte)(payload & 0xFF)};
            byte[] lengthBytes = encodeLength(payloadBytes.length);
            return concat(tagBytes, lengthBytes, payloadBytes);
        }
    }

    public static byte[] buildTlv(int tag, byte[] payload) {
        byte[] tagBytes = encodeTag(tag);
        byte[] lengthBytes = encodeLength(payload.length);
        return concat(tagBytes, lengthBytes, payload);
    }

    private static int[] readOneTLV(byte[] tlv, int index) {
        int firstByte = tlv[index++];
        boolean isTwoByteTag = (firstByte & 0x1F) == 0x1F;

        if (isTwoByteTag){
            int secondByte = tlv[index++];

            firstByte = (firstByte << 8) | (secondByte & 0xFF);
        }

        int length = 0;

        int firstLengthByte = tlv[index];

        if ((firstLengthByte & 0x80) == 0){
            length = firstLengthByte;
            index++;
        }
        else {
            int numberOfBytesLength = tlv[index++] & 0x7F;

            for (int i = 0; i < numberOfBytesLength; i++) {
                length = (length << 8);

                length = length | (tlv[index++] & 0xFF);
            }
        }

        // mask the tag to be unsigned, here because of the registry keys
        if (isTwoByteTag) {
            firstByte = firstByte & 0xFFFF;
        } else {
            firstByte = firstByte & 0xFF;
        }

        int[] parsed = new int[] {
                firstByte,
                length,
                index,
                0,
        };

        parsed[parsed.length - 1] = index + length;

        return parsed;
    }

    /// This extracts a tlv with tag
    public static byte[] extractOneTLV(byte[] tlv, int index) {
        int[] parsed = readOneTLV( tlv, index );

        return Arrays.copyOfRange(tlv, index, parsed[parsed.length - 1]);
    }
}