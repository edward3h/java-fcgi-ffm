/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FcgiConnectionTest {
    private static final int REQUEST_ID = 1;
    private static final int FCGI_RESPONDER = 1;

    @Test
    void readsTheEnvironmentFromBeginRequestAndParams() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("REQUEST_METHOD", "GET");
        params.put("SCRIPT_NAME", "/app.fcgi");

        var wire = new ByteArrayOutputStream();
        writeBeginRequest(wire, FCGI_RESPONDER);
        writeParamsRecord(wire, NameValuePairs.encode(params));
        writeParamsRecord(wire, new byte[0]); // terminator

        var connection = newConnectionOverFixedInput(wire.toByteArray());
        Map<String, String> env = connection.readRequestHeader();

        assertThat(env).containsExactlyEntriesIn(params);
    }

    @Test
    void stdinStreamReturnsBytesFromStdinRecordsUntilTheEmptyTerminator() throws Exception {
        var wire = new ByteArrayOutputStream();
        writeBeginRequest(wire, FCGI_RESPONDER);
        writeParamsRecord(wire, new byte[0]);
        writeStdinRecord(wire, "hello".getBytes());
        writeStdinRecord(wire, new byte[0]); // terminator

        var connection = newConnectionOverFixedInput(wire.toByteArray());
        connection.readRequestHeader();
        byte[] body = connection.stdin().readAllBytes();

        assertThat(new String(body)).isEqualTo("hello");
    }

    @Test
    void stdoutStreamFramesWrittenBytesAsStdoutRecordsThenWritesEndRequestOnFinish() throws Exception {
        var wire = new ByteArrayOutputStream();
        writeBeginRequest(wire, FCGI_RESPONDER);
        writeParamsRecord(wire, new byte[0]);
        writeStdinRecord(wire, new byte[0]);

        var responseOut = new PipedOutputStream();
        var responseIn = new PipedInputStream(responseOut);
        var connection = new FcgiConnection(new ByteArrayInputStream(wire.toByteArray()), responseOut);
        connection.readRequestHeader();

        try (var out = connection.stdout()) {
            out.write("hi".getBytes());
        }
        connection.finish(0);

        var stdoutHeader = RecordHeader.readFrom(responseIn);
        assertThat(stdoutHeader.type()).isEqualTo(RecordType.STDOUT);
        assertThat(stdoutHeader.contentLength()).isEqualTo(2);
        byte[] body = responseIn.readNBytes(2);
        assertThat(new String(body)).isEqualTo("hi");

        var stdoutTerminator = RecordHeader.readFrom(responseIn);
        assertThat(stdoutTerminator.type()).isEqualTo(RecordType.STDOUT);
        assertThat(stdoutTerminator.contentLength()).isEqualTo(0);

        var endRequest = RecordHeader.readFrom(responseIn);
        assertThat(endRequest.type()).isEqualTo(RecordType.END_REQUEST);
    }

    @Test
    void throwsOnATruncatedBeginRequestBody() throws Exception {
        var wire = new ByteArrayOutputStream();
        new RecordHeader(1, RecordType.BEGIN_REQUEST, REQUEST_ID, 1, 0).writeTo(wire);
        wire.write(new byte[] {1}); // only 1 byte of what should be at least a 2-byte role field

        var connection = newConnectionOverFixedInput(wire.toByteArray());
        assertThrows(IOException.class, connection::readRequestHeader);
    }

    @Test
    void wrapsMalformedParamsAsIOException() throws Exception {
        var wire = new ByteArrayOutputStream();
        writeBeginRequest(wire, FCGI_RESPONDER);
        writeParamsRecord(wire, new byte[] {5, 1, 'A'}); // claims a 5-byte name, only 1 byte follows
        writeParamsRecord(wire, new byte[0]);

        var connection = newConnectionOverFixedInput(wire.toByteArray());
        assertThrows(IOException.class, connection::readRequestHeader);
    }

    @Test
    void throwsOnAParamsRecordWithTheWrongRequestId() throws Exception {
        var wire = new ByteArrayOutputStream();
        writeBeginRequest(wire, FCGI_RESPONDER);
        new RecordHeader(1, RecordType.PARAMS, REQUEST_ID + 1, 0, 0).writeTo(wire); // wrong request ID

        var connection = newConnectionOverFixedInput(wire.toByteArray());
        assertThrows(IOException.class, connection::readRequestHeader);
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(type, runnable);
    }

    private interface ThrowingRunnable extends org.junit.jupiter.api.function.Executable {}

    private static FcgiConnection newConnectionOverFixedInput(byte[] wire) {
        return new FcgiConnection(new ByteArrayInputStream(wire), new ByteArrayOutputStream());
    }

    private static void writeBeginRequest(ByteArrayOutputStream wire, int role) throws Exception {
        byte[] body = {(byte) (role >> 8), (byte) role, 0, 0, 0, 0, 0, 0};
        new RecordHeader(1, RecordType.BEGIN_REQUEST, REQUEST_ID, body.length, 0).writeTo(wire);
        wire.write(body);
    }

    private static void writeParamsRecord(ByteArrayOutputStream wire, byte[] content) throws Exception {
        new RecordHeader(1, RecordType.PARAMS, REQUEST_ID, content.length, 0).writeTo(wire);
        wire.write(content);
    }

    private static void writeStdinRecord(ByteArrayOutputStream wire, byte[] content) throws Exception {
        new RecordHeader(1, RecordType.STDIN, REQUEST_ID, content.length, 0).writeTo(wire);
        wire.write(content);
    }
}
