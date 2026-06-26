/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import red.ethel.fcgi.core.FCGIException;

/// Minimal libc shim: just the syscalls FastCGI needs to accept a connection
/// on an inherited socket (fd 0, bound and listening by the web server's
/// process manager before this process is spawned) and exchange bytes on it.
/// Uses the JVM's own libc via the linker's default lookup, so there's no
/// separate native library to install or locate.
public final class Posix {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG;

    /// ENOTSOCK on Linux: the only errno value from getsockname() that means
    /// "this fd is not a socket at all" (as opposed to some other failure).
    private static final int ENOTSOCK = 88;

    /// EINTR on Linux: a blocking syscall was interrupted by a signal before
    /// it could complete. Not a real failure -- the call must simply be
    /// retried. Plausible under mod_fcgid, which is the actual deployment
    /// target for this shim.
    private static final int EINTR = 4;

    private static final long SOCKADDR_STORAGE_SIZE = 128;

    private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");
    private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout().byteOffset(groupElement("errno"));

    private static final MethodHandle ACCEPT =
            downcallWithErrno("accept", FunctionDescriptor.of(C_INT, C_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle READ =
            downcallWithErrno("read", FunctionDescriptor.of(C_LONG, C_INT, ValueLayout.ADDRESS, C_LONG));
    private static final MethodHandle WRITE =
            downcallWithErrno("write", FunctionDescriptor.of(C_LONG, C_INT, ValueLayout.ADDRESS, C_LONG));
    private static final MethodHandle CLOSE = downcall("close", FunctionDescriptor.of(C_INT, C_INT));
    private static final MethodHandle GETSOCKNAME = downcallWithErrno(
            "getsockname", FunctionDescriptor.of(C_INT, C_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private Posix() {}

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), descriptor);
    }

    private static MethodHandle downcallWithErrno(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), descriptor, ERRNO);
    }

    /// Blocks until a peer connects to {@code listenFd}, returning the new
    /// connection's fd. Retries on EINTR: a signal interrupting `accept(2)`
    /// is not a real failure, just a reason to call it again.
    public static int accept(int listenFd) {
        try (Arena arena = Arena.ofConfined()) {
            var capturedState = arena.allocate(Linker.Option.captureStateLayout());
            while (true) {
                int clientFd =
                        (int) ACCEPT.invokeExact(capturedState, listenFd, MemorySegment.NULL, MemorySegment.NULL);
                if (clientFd >= 0) {
                    return clientFd;
                }
                int errno = capturedState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
                if (errno == EINTR) {
                    continue;
                }
                throw new FCGIException("accept", errno);
            }
        } catch (FCGIException e) {
            throw e;
        } catch (Throwable t) {
            throw new FCGIException("accept", t);
        }
    }

    /// Reads up to {@code len} bytes into {@code buf} starting at {@code off}.
    /// Returns 0 at end of stream (peer closed), matching `read(2)` semantics.
    /// Retries on EINTR.
    public static int read(int fd, byte[] buf, int off, int len) {
        Objects.checkFromIndexSize(off, len, buf.length);
        if (len == 0) {
            return 0;
        }
        try (Arena arena = Arena.ofConfined()) {
            var capturedState = arena.allocate(Linker.Option.captureStateLayout());
            var nativeBuf = arena.allocate(len);
            while (true) {
                long n = (long) READ.invokeExact(capturedState, fd, nativeBuf, (long) len);
                if (n >= 0) {
                    if (n > 0) {
                        MemorySegment.copy(nativeBuf, ValueLayout.JAVA_BYTE, 0, buf, off, (int) n);
                    }
                    return (int) n;
                }
                int errno = capturedState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
                if (errno == EINTR) {
                    continue;
                }
                throw new FCGIException("read", errno);
            }
        } catch (FCGIException e) {
            throw e;
        } catch (Throwable t) {
            throw new FCGIException("read", t);
        }
    }

    /// Writes exactly {@code len} bytes from {@code buf} starting at {@code off},
    /// looping internally if a single `write(2)` call accepts fewer. Retries
    /// on EINTR.
    public static void write(int fd, byte[] buf, int off, int len) {
        Objects.checkFromIndexSize(off, len, buf.length);
        if (len == 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            var capturedState = arena.allocate(Linker.Option.captureStateLayout());
            var nativeBuf = arena.allocate(len);
            MemorySegment.copy(buf, off, nativeBuf, ValueLayout.JAVA_BYTE, 0, len);
            long written = 0;
            while (written < len) {
                long n = (long) WRITE.invokeExact(capturedState, fd, nativeBuf.asSlice(written), len - written);
                if (n < 0) {
                    int errno = capturedState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
                    if (errno == EINTR) {
                        continue;
                    }
                    throw new FCGIException("write", errno);
                }
                written += n;
            }
        } catch (FCGIException e) {
            throw e;
        } catch (Throwable t) {
            throw new FCGIException("write", t);
        }
    }

    public static void close(int fd) {
        try {
            int result = (int) CLOSE.invokeExact(fd);
            if (result != 0) {
                throw new FCGIException("close failed");
            }
        } catch (FCGIException e) {
            throw e;
        } catch (Throwable t) {
            throw new FCGIException("close", t);
        }
    }

    /// True if {@code fd} is a socket. Used to distinguish FastCGI (fd 0 is a
    /// socket inherited from the process manager) from plain CGI (fd 0 is a
    /// pipe).
    public static boolean isSocket(int fd) {
        try (Arena arena = Arena.ofConfined()) {
            var capturedState = arena.allocate(Linker.Option.captureStateLayout());
            var sockAddr = arena.allocate(SOCKADDR_STORAGE_SIZE);
            var addrLen = arena.allocate(C_INT);
            addrLen.set(C_INT, 0, (int) SOCKADDR_STORAGE_SIZE);
            int result = (int) GETSOCKNAME.invokeExact(capturedState, fd, sockAddr, addrLen);
            if (result == 0) {
                return true;
            }
            int errno = capturedState.get(ValueLayout.JAVA_INT, ERRNO_OFFSET);
            return errno != ENOTSOCK;
        } catch (Throwable t) {
            throw new FCGIException("getsockname", t);
        }
    }

    public static InputStream inputStream(int fd) {
        return new FdInputStream(fd);
    }

    public static OutputStream outputStream(int fd) {
        return new FdOutputStream(fd);
    }
}
