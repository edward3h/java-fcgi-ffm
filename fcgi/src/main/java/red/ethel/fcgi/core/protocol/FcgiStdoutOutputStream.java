/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import java.io.IOException;
import java.io.OutputStream;

/// Frames bytes written by the application handler into FCGI_STDOUT records,
/// buffering a whole write() call into one or more records instead of one
/// native call per byte.
final class FcgiStdoutOutputStream extends OutputStream {
    private static final int MAX_CONTENT_LENGTH = 0xFFFF;

    private final OutputStream rawOut;
    private final int requestId;
    private boolean closed;

    FcgiStdoutOutputStream(OutputStream rawOut, int requestId) {
        this.rawOut = rawOut;
        this.requestId = requestId;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        int offset = off;
        while (remaining > 0) {
            int chunk = Math.min(remaining, MAX_CONTENT_LENGTH);
            new RecordHeader(1, RecordType.STDOUT, requestId, chunk, 0).writeTo(rawOut);
            rawOut.write(b, offset, chunk);
            offset += chunk;
            remaining -= chunk;
        }
    }

    @Override
    public void flush() throws IOException {
        rawOut.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        // An empty STDOUT record signals end-of-stream, per the FastCGI spec.
        new RecordHeader(1, RecordType.STDOUT, requestId, 0, 0).writeTo(rawOut);
        rawOut.flush();
    }
}
