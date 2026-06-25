/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/// Test-only libc bindings used to set up real sockets so {@link Posix} can be
/// exercised against the kernel instead of mocks. These calls have no place in
/// the production shim: production never creates its own sockets, it only ever
/// accepts on one already bound and listening, inherited on fd 0.
final class PosixTestSupport {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;

    private static final int AF_INET = 2;
    private static final int AF_UNIX = 1;
    private static final int SOCK_STREAM = 1;

    private static final MethodHandle SOCKET = downcall("socket", FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT));
    private static final MethodHandle BIND =
            downcall("bind", FunctionDescriptor.of(C_INT, C_INT, ValueLayout.ADDRESS, C_INT));
    private static final MethodHandle LISTEN = downcall("listen", FunctionDescriptor.of(C_INT, C_INT, C_INT));
    private static final MethodHandle SOCKETPAIR =
            downcall("socketpair", FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT, ValueLayout.ADDRESS));

    private PosixTestSupport() {}

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), descriptor);
    }

    /// Binds and listens on 127.0.0.1:port (TCP), returning the listening fd.
    static int listenOn(int port) {
        try (Arena arena = Arena.ofConfined()) {
            int fd = (int) SOCKET.invokeExact(AF_INET, SOCK_STREAM, 0);
            if (fd < 0) {
                throw new IllegalStateException("socket() failed");
            }
            var addr = arena.allocate(16);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) AF_INET);
            addr.set(ValueLayout.JAVA_BYTE, 2, (byte) (port >> 8));
            addr.set(ValueLayout.JAVA_BYTE, 3, (byte) port);
            // remaining bytes (sin_addr, sin_zero) stay zero: INADDR_ANY
            if ((int) BIND.invokeExact(fd, addr, 16) != 0) {
                throw new IllegalStateException("bind() failed");
            }
            if ((int) LISTEN.invokeExact(fd, 1) != 0) {
                throw new IllegalStateException("listen() failed");
            }
            return fd;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /// Returns a connected pair of fds (a loopback pipe) for testing read/write
    /// without any real networking.
    static int[] socketPair() {
        try (Arena arena = Arena.ofConfined()) {
            var fds = arena.allocate(8); // 2 x int
            if ((int) SOCKETPAIR.invokeExact(AF_UNIX, SOCK_STREAM, 0, fds) != 0) {
                throw new IllegalStateException("socketpair() failed");
            }
            return new int[] {fds.get(C_INT, 0), fds.get(C_INT, 4)};
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
