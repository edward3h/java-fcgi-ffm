/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/// Codec for the FCGI_PARAMS name-value pair wire format: each name and value
/// is preceded by a length that is 1 byte if < 128, or a 4-byte big-endian
/// length (high bit set on the first byte) otherwise.
public final class NameValuePairs {
    private NameValuePairs() {}

    public static Map<String, String> decode(byte[] block) {
        try {
            return decodeUnchecked(block);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Malformed FCGI_PARAMS block", e);
        }
    }

    private static Map<String, String> decodeUnchecked(byte[] block) {
        var result = new LinkedHashMap<String, String>();
        int pos = 0;
        while (pos < block.length) {
            int[] nameLen = new int[1];
            pos = readLength(block, pos, nameLen);
            int[] valueLen = new int[1];
            pos = readLength(block, pos, valueLen);
            String name = new String(block, pos, nameLen[0], StandardCharsets.UTF_8);
            pos += nameLen[0];
            String value = new String(block, pos, valueLen[0], StandardCharsets.UTF_8);
            pos += valueLen[0];
            result.put(name, value);
        }
        return result;
    }

    private static int readLength(byte[] block, int pos, int[] out) {
        int first = block[pos] & 0xFF;
        if ((first & 0x80) == 0) {
            out[0] = first;
            return pos + 1;
        }
        out[0] = ((first & 0x7F) << 24)
                | ((block[pos + 1] & 0xFF) << 16)
                | ((block[pos + 2] & 0xFF) << 8)
                | (block[pos + 3] & 0xFF);
        return pos + 4;
    }

    public static byte[] encode(Map<String, String> pairs) {
        var out = new ByteArrayOutputStream();
        for (var entry : pairs.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            writeLength(out, name.length);
            writeLength(out, value.length);
            out.write(name, 0, name.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else {
            out.write(0x80 | ((length >> 24) & 0x7F));
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }
}
