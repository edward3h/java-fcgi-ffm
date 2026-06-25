/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class RecordHeaderTest {
    @Test
    void readsAKnownByteSequence() throws Exception {
        // version=1, type=STDIN(5), requestId=1, contentLength=4, padding=2
        byte[] bytes = {1, 5, 0, 1, 0, 4, 2, 0};
        var header = RecordHeader.readFrom(new ByteArrayInputStream(bytes));
        assertThat(header.version()).isEqualTo(1);
        assertThat(header.type()).isEqualTo(RecordType.STDIN);
        assertThat(header.requestId()).isEqualTo(1);
        assertThat(header.contentLength()).isEqualTo(4);
        assertThat(header.paddingLength()).isEqualTo(2);
    }

    @Test
    void writeThenReadRoundTrips() throws Exception {
        var header = new RecordHeader(1, RecordType.STDOUT, 42, 1000, 3);
        var out = new ByteArrayOutputStream();
        header.writeTo(out);
        var read = RecordHeader.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertThat(read).isEqualTo(header);
    }

    @Test
    void throwsOnAnUnknownRecordType() {
        byte[] bytes = {1, 99, 0, 1, 0, 0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> RecordHeader.readFrom(new ByteArrayInputStream(bytes)));
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(type, runnable);
    }

    private interface ThrowingRunnable extends org.junit.jupiter.api.function.Executable {}
}
