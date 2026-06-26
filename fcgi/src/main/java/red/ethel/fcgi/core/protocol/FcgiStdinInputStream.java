/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import java.io.IOException;
import java.io.InputStream;

/// Presents the FCGI_STDIN records for one request as a plain InputStream,
/// pulling more records from the connection as the buffered ones are drained.
final class FcgiStdinInputStream extends InputStream {
    private final InputStream rawIn;
    private final int requestId;
    private byte[] buffer = new byte[0];
    private int pos;
    private boolean eof;

    FcgiStdinInputStream(InputStream rawIn, int requestId) {
        this.rawIn = rawIn;
        this.requestId = requestId;
    }

    @Override
    public int read() throws IOException {
        if (!fillIfNeeded()) {
            return -1;
        }
        return buffer[pos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0; // InputStream contract: must not block for a zero-length read
        }
        if (!fillIfNeeded()) {
            return -1;
        }
        int n = Math.min(len, buffer.length - pos);
        System.arraycopy(buffer, pos, b, off, n);
        pos += n;
        return n;
    }

    private boolean fillIfNeeded() throws IOException {
        if (pos < buffer.length) {
            return true;
        }
        if (eof) {
            return false;
        }
        var header = RecordHeader.readFrom(rawIn);
        if (header.type() != RecordType.STDIN || header.requestId() != requestId) {
            throw new IOException("Expected STDIN for request " + requestId + ", got " + header);
        }
        if (header.contentLength() == 0) {
            eof = true;
            return false;
        }
        buffer = rawIn.readNBytes(header.contentLength());
        rawIn.skipNBytes(header.paddingLength());
        pos = 0;
        return true;
    }
}
