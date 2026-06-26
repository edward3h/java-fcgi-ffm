/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/// The fixed 8-byte header that precedes every FastCGI record.
public record RecordHeader(int version, RecordType type, int requestId, int contentLength, int paddingLength) {
    public static final int LENGTH = 8;
    private static final int VERSION_1 = 1;

    public static RecordHeader readFrom(InputStream in) throws IOException {
        byte[] buf = in.readNBytes(LENGTH);
        if (buf.length < LENGTH) {
            throw new EOFException("Expected FastCGI record header, got " + buf.length + " bytes");
        }
        int version = buf[0] & 0xFF;
        RecordType type = RecordType.fromCode(buf[1] & 0xFF);
        int requestId = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int contentLength = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
        int paddingLength = buf[6] & 0xFF;
        return new RecordHeader(version, type, requestId, contentLength, paddingLength);
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(new byte[] {
            (byte) VERSION_1,
            (byte) type.code,
            (byte) (requestId >> 8),
            (byte) requestId,
            (byte) (contentLength >> 8),
            (byte) contentLength,
            (byte) paddingLength,
            0 // reserved, must be 0
        });
    }
}
