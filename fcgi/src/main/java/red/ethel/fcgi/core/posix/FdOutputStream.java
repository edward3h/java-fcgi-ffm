/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

import java.io.IOException;
import java.io.OutputStream;
import red.ethel.fcgi.core.FCGIException;

/// Writes directly to a raw fd via {@link Posix#write}. Construct through
/// {@link Posix#outputStream(int)}.
final class FdOutputStream extends OutputStream {
    private final int fd;

    FdOutputStream(int fd) {
        this.fd = fd;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            Posix.write(fd, b, off, len);
        } catch (FCGIException e) {
            throw new IOException(e);
        }
    }
}
