/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

import java.io.IOException;
import java.io.InputStream;
import red.ethel.fcgi.core.FCGIException;

/// Reads directly from a raw fd via {@link Posix#read}. Construct through
/// {@link Posix#inputStream(int)}.
final class FdInputStream extends InputStream {
    private final int fd;

    FdInputStream(int fd) {
        this.fd = fd;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            int n = Posix.read(fd, b, off, len);
            return n == 0 ? -1 : n;
        } catch (FCGIException e) {
            throw new IOException(e);
        }
    }
}
