/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import static com.google.common.truth.Truth.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameValuePairsTest {
    @Test
    void decodesASingleShortNameAndValue() {
        // 1-byte lengths: name "A" (len 1), value "BB" (len 2)
        byte[] block = {1, 2, 'A', 'B', 'B'};
        assertThat(NameValuePairs.decode(block)).containsExactly("A", "BB");
    }

    @Test
    void decodesMultiplePairs() {
        byte[] block = {1, 1, 'A', 'X', 2, 1, 'B', 'B', 'Y'};
        assertThat(NameValuePairs.decode(block)).containsExactly("A", "X", "BB", "Y");
    }

    @Test
    void decodesALengthOf128OrMoreUsingTheFourByteForm() {
        String longValue = "v".repeat(200);
        var out = new java.io.ByteArrayOutputStream();
        out.write(1); // name length = 1
        // value length = 200, encoded as 4 bytes with high bit set on the first
        out.write(0x80 | ((200 >> 24) & 0x7F));
        out.write((200 >> 16) & 0xFF);
        out.write((200 >> 8) & 0xFF);
        out.write(200 & 0xFF);
        out.write('N');
        out.write(longValue.getBytes(), 0, longValue.length());
        assertThat(NameValuePairs.decode(out.toByteArray())).containsExactly("N", longValue);
    }

    @Test
    void encodeThenDecodeRoundTrips() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("REQUEST_METHOD", "GET");
        pairs.put("PATH_INFO", "v".repeat(150)); // forces the 4-byte length form
        byte[] encoded = NameValuePairs.encode(pairs);
        assertThat(NameValuePairs.decode(encoded)).containsExactlyEntriesIn(pairs);
    }

    @Test
    void encodesExactlyOneTwentySevenBytesUsingTheOneByteForm() {
        // 127 is the largest length that must use the 1-byte form
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("N", "v".repeat(127));
        byte[] encoded = NameValuePairs.encode(pairs);
        assertThat(encoded[0]).isEqualTo((byte) 1); // name length byte: 1
        assertThat(encoded[1]).isEqualTo((byte) 127); // value length byte: 127, not high-bit-set
        assertThat(NameValuePairs.decode(encoded)).containsExactlyEntriesIn(pairs);
    }

    @Test
    void encodesExactlyOneTwentyEightBytesUsingTheFourByteForm() {
        // 128 is the smallest length that must use the 4-byte form
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("N", "v".repeat(128));
        byte[] encoded = NameValuePairs.encode(pairs);
        assertThat(encoded[0]).isEqualTo((byte) 1); // name length byte: 1
        assertThat(encoded[1] & 0x80).isEqualTo(0x80); // value length: high bit set, 4-byte form
        assertThat(NameValuePairs.decode(encoded)).containsExactlyEntriesIn(pairs);
    }

    @Test
    void throwsOnATruncatedBlock() {
        byte[] truncated = {5, 1, 'A'}; // claims a 5-byte name but only 1 byte follows
        assertThrows(IllegalArgumentException.class, () -> NameValuePairs.decode(truncated));
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(type, runnable);
    }

    private interface ThrowingRunnable extends org.junit.jupiter.api.function.Executable {}
}
