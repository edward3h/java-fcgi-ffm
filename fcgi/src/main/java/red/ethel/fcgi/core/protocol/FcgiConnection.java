/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/// Speaks the FastCGI wire protocol on one accepted connection: reads the
/// BEGIN_REQUEST + PARAMS records to build the request environment, then
/// exposes the stdin/stdout streams the application handler reads/writes,
/// and writes END_REQUEST when the handler is done.
///
/// Handles exactly one request per connection, matching both `libfcgi`'s
/// behaviour (it rejects multiplexed connections) and this project's actual
/// deployment (Apache `mod_fcgid` spawning one process per request burst).
/// FCGI_GET_VALUES and FCGI_ABORT_REQUEST are not handled - see the plan
/// this class was introduced under for why that's an accepted limitation.
public final class FcgiConnection implements AutoCloseable {
    private static final int FCGI_RESPONDER = 1;

    private final InputStream rawIn;
    private final OutputStream rawOut;
    private int requestId;

    public FcgiConnection(InputStream rawIn, OutputStream rawOut) {
        this.rawIn = rawIn;
        this.rawOut = rawOut;
    }

    /// Reads BEGIN_REQUEST followed by zero or more PARAMS records (terminated
    /// by an empty one), and returns the assembled CGI environment.
    public Map<String, String> readRequestHeader() throws IOException {
        var begin = RecordHeader.readFrom(rawIn);
        if (begin.type() != RecordType.BEGIN_REQUEST) {
            throw new IOException("Expected BEGIN_REQUEST, got " + begin.type());
        }
        requestId = begin.requestId();
        byte[] body = rawIn.readNBytes(begin.contentLength() + begin.paddingLength());
        if (body.length < 2) {
            throw new IOException("Truncated BEGIN_REQUEST body: expected at least 2 bytes, got " + body.length);
        }
        int role = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        if (role != FCGI_RESPONDER) {
            throw new IOException("Unsupported FastCGI role: " + role);
        }

        var paramsBytes = new ByteArrayOutputStream();
        RecordHeader header;
        do {
            header = RecordHeader.readFrom(rawIn);
            if (header.type() != RecordType.PARAMS) {
                throw new IOException("Expected PARAMS, got " + header.type());
            }
            paramsBytes.write(rawIn.readNBytes(header.contentLength()));
            rawIn.skipNBytes(header.paddingLength());
        } while (header.contentLength() > 0);

        try {
            return NameValuePairs.decode(paramsBytes.toByteArray());
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed FCGI_PARAMS", e);
        }
    }

    public InputStream stdin() {
        return new FcgiStdinInputStream(rawIn, requestId);
    }

    public OutputStream stdout() {
        return new FcgiStdoutOutputStream(rawOut, requestId);
    }

    /// Writes FCGI_END_REQUEST with the given application exit status.
    public void finish(int appStatus) throws IOException {
        byte[] body = new byte[8];
        body[0] = (byte) (appStatus >> 24);
        body[1] = (byte) (appStatus >> 16);
        body[2] = (byte) (appStatus >> 8);
        body[3] = (byte) appStatus;
        // body[4] = protocolStatus = FCGI_REQUEST_COMPLETE (0); rest reserved.
        new RecordHeader(1, RecordType.END_REQUEST, requestId, body.length, 0).writeTo(rawOut);
        rawOut.write(body);
        rawOut.flush();
    }

    @Override
    public void close() throws IOException {
        rawOut.flush();
    }
}
