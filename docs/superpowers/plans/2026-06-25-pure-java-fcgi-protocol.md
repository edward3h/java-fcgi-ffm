# Pure-Java FastCGI Protocol Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the FFM wrapper around the system `libfcgi` C library with a hybrid design: a minimal FFM shim around four POSIX syscalls, plus a pure-Java implementation of the FastCGI wire protocol.

**Architecture:** `FCGINative.java` (323 lines hand-binding 9 `libfcgi` functions against a hardcoded `.so` path and a manually-laid-out `FCGX_Request` struct) is deleted entirely. It's replaced by two new packages under the `fcgi` module:
- `red.ethel.fcgi.core.posix` — a ~5-function FFM shim (`accept`, `read`, `write`, `close`, `isSocket`) against the JVM's own libc via `Linker.defaultLookup()`. No external library, no hardcoded path, no struct layout beyond a throwaway `sockaddr` buffer.
- `red.ethel.fcgi.core.protocol` — pure Java: FastCGI record header framing, the FCGI_PARAMS name-value pair codec, and per-connection orchestration (BEGIN_REQUEST/PARAMS/STDIN parsing, STDOUT/END_REQUEST writing).

`FcgiService` is rewired to use these instead of `FCGINative`, but its threading model is **unchanged**: connection handling still runs one-platform-thread-per-connection (`Executors.newThreadPerTaskExecutor`), because `accept`/`read`/`write` invoked via FFM still pin their carrier thread for the call's duration regardless of which native library they call into — switching to virtual threads here would reproduce the exact starvation bug fixed in commit `8a55d05`, just for `read`/`write` instead of `accept`. Do not change `defaultExecutor()` to virtual threads.

The deployment model (DreamHost via Apache `mod_fcgid`, confirmed to disallow persistent daemons) means the app is still spawned per-request-burst with a socket pre-bound to fd 0 — so `accept()` on an inherited fd remains necessary; pure-Java-only (e.g. Java's own `UnixDomainSocketAddress`/`ServerSocketChannel`) is not viable here.

**Tech Stack:** Java 25, `java.lang.foreign` (FFM), JUnit 5, Google Truth, existing Gradle conventions (`java-convention`, `graalvm-native-convention`).

---

## Context

`java-fcgi-ffm`'s `fcgi` module wraps the system `libfcgi` C library via hand-written FFM bindings in `FCGINative.java`. This has three concrete costs, surfaced while investigating against the original `fcgi2` source (`/home/edward/github/FastCGI-Archives/fcgi2`):

1. **Deployment friction**: requires `libfcgi-dev` installed system-wide, with a hardcoded library path (`fcgi/src/main/java/red/ethel/fcgi/core/FCGINative.java:62`, flagged `// TODO configurable library path`) that only works on Debian/Ubuntu x86_64.
2. **Inefficient I/O**: all request/response bytes cross the FFM boundary one at a time (`FCGX_GetChar`/`FCGX_PutChar`); the bulk variants (`FCGX_GetStr`/`FCGX_PutStr`) are implemented but commented out (`FCGINative.java:246-256`, `300-312`).
3. **Fragile bindings**: the hand-written `FCGX_Request` struct layout (`FCGINative.java:39-55`) must track `libfcgi`'s internal ABI, which the project doesn't control and which the upstream license dates to 1995-96 (essentially unmaintained).

Investigation of `fcgi2` showed the protocol logic (8-byte record headers, FCGI_PARAMS name-value pair encoding, the request state machine) is a small, OS-independent, mechanically portable slice of the ~7,800-line C library — the rest is socket/signal/async-I/O glue that Java's standard library already does better. The user confirmed DreamHost (the actual deployment target) does not support running a persistent daemon, which rules out switching to FastCGI "external" mode (where the app binds its own listening socket and needs zero native code) — Apache still spawns the process with a pre-bound socket on fd 0, so accepting on an inherited fd is unavoidable, and that requires *some* native call.

This plan implements the resulting hybrid: the smallest possible native shim (4 production syscalls) plus a pure-Java protocol layer, replacing 323 lines of brittle library-specific bindings with a much smaller, portable, dependency-free native surface and a protocol implementation the project fully owns and controls.

**Outcome:** `apt install libfcgi-dev` is no longer required anywhere (dev machine or DreamHost), the hardcoded `.so` path is gone, and stdin/stdout I/O happens in buffered chunks instead of one native call per byte. Threading behaviour is unchanged from the current (already-correct) platform-thread design.

---

## Chunk 1: POSIX native shim

**Files:**
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/posix/Posix.java`
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/posix/FdInputStream.java`
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/posix/FdOutputStream.java`
- Create: `fcgi/src/test/java/red/ethel/fcgi/core/posix/PosixTestSupport.java`
- Create: `fcgi/src/test/java/red/ethel/fcgi/core/posix/PosixTest.java`

This chunk produces a fully tested, dependency-free replacement for everything `FCGINative` currently gets from `libfcgi` at the I/O level: accepting a connection on an inherited fd, reading/writing bytes on it, closing it, and telling FastCGI mode apart from CGI mode. It deliberately does **not** touch `FcgiService` yet — that's Chunk 4, once the protocol layer (Chunks 2-3) exists to consume this.

`Posix` only exposes what production code needs (`accept`, `read`, `write`, `close`, `isSocket`). `socket`/`bind`/`listen`/`socketpair` are test-only (in `PosixTestSupport`, package-private, `src/test`) — production never creates its own sockets, it only ever accepts on an inherited one, so those calls have no place in the shipped shim.

- [ ] **Step 1: Write the failing test for read/write round-trip over a socket pair**

Create `fcgi/src/test/java/red/ethel/fcgi/core/posix/PosixTestSupport.java`:

```java
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

    private static final MethodHandle SOCKET =
            downcall("socket", FunctionDescriptor.of(C_INT, C_INT, C_INT, C_INT));
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
```

Create `fcgi/src/test/java/red/ethel/fcgi/core/posix/PosixTest.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class PosixTest {
    @Test
    void writeThenReadRoundTripsBytesOverASocketPair() throws IOException {
        int[] fds = PosixTestSupport.socketPair();
        try {
            byte[] message = "hello fastcgi".getBytes();
            Posix.write(fds[0], message, 0, message.length);
            byte[] received = new byte[message.length];
            int n = Posix.read(fds[1], received, 0, received.length);
            assertThat(n).isEqualTo(message.length);
            assertThat(received).isEqualTo(message);
        } finally {
            Posix.close(fds[0]);
            Posix.close(fds[1]);
        }
    }

    @Test
    void readReturnsZeroAtEndOfStreamAfterPeerCloses() throws IOException {
        int[] fds = PosixTestSupport.socketPair();
        Posix.close(fds[0]);
        try {
            byte[] buf = new byte[16];
            assertThat(Posix.read(fds[1], buf, 0, buf.length)).isEqualTo(0);
        } finally {
            Posix.close(fds[1]);
        }
    }

    @Test
    void acceptReturnsAConnectedClientFdWhenAPeerConnects() throws Exception {
        int port = findFreePort();
        int listenFd = PosixTestSupport.listenOn(port);
        try {
            try (Socket client = new Socket("127.0.0.1", port)) {
                int clientFd = Posix.accept(listenFd);
                try {
                    assertThat(clientFd).isAtLeast(0);
                } finally {
                    Posix.close(clientFd);
                }
            }
        } finally {
            Posix.close(listenFd);
        }
    }

    @Test
    void isSocketIsTrueForARealSocketAndFalseForARegularPipe() throws Exception {
        int[] fds = PosixTestSupport.socketPair();
        try {
            assertThat(Posix.isSocket(fds[0])).isTrue();
        } finally {
            Posix.close(fds[0]);
            Posix.close(fds[1]);
        }
        // fd 1 (stdout) under the Gradle test JVM is a pipe/file, never a socket.
        assertThat(Posix.isSocket(1)).isFalse();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile (Posix doesn't exist yet)**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.posix.PosixTest"`
Expected: FAIL — compilation error, `Posix` class not found.

- [ ] **Step 3: Implement `FdInputStream` and `FdOutputStream`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/posix/FdInputStream.java`:

```java
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
```

Create `fcgi/src/main/java/red/ethel/fcgi/core/posix/FdOutputStream.java`:

```java
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
```

- [ ] **Step 4: Implement `Posix`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/posix/Posix.java`:

```java
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
    private static final long SOCKADDR_STORAGE_SIZE = 128;

    private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");
    private static final long ERRNO_OFFSET = Linker.Option.captureStateLayout().byteOffset(groupElement("errno"));

    private static final MethodHandle ACCEPT = downcall(
            "accept", FunctionDescriptor.of(C_INT, C_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle READ =
            downcall("read", FunctionDescriptor.of(C_LONG, C_INT, ValueLayout.ADDRESS, C_LONG));
    private static final MethodHandle WRITE =
            downcall("write", FunctionDescriptor.of(C_LONG, C_INT, ValueLayout.ADDRESS, C_LONG));
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
    /// connection's fd.
    public static int accept(int listenFd) {
        try {
            int clientFd = (int) ACCEPT.invokeExact(listenFd, MemorySegment.NULL, MemorySegment.NULL);
            if (clientFd < 0) {
                throw new FCGIException("accept failed");
            }
            return clientFd;
        } catch (FCGIException e) {
            throw e;
        } catch (Throwable t) {
            throw new FCGIException("accept", t);
        }
    }

    /// Reads up to {@code len} bytes into {@code buf} starting at {@code off}.
    /// Returns 0 at end of stream (peer closed), matching `read(2)` semantics.
    public static int read(int fd, byte[] buf, int off, int len) {
        try (Arena arena = Arena.ofConfined()) {
            var native_ = arena.allocate(len);
            long n = (long) READ.invokeExact(fd, native_, (long) len);
            if (n < 0) {
                throw new FCGIException("read failed");
            }
            if (n > 0) {
                MemorySegment.copy(native_, ValueLayout.JAVA_BYTE, 0, buf, off, (int) n);
            }
            return (int) n;
        } catch (FCGIException e) {
            throw e;
        } catch (Throwable t) {
            throw new FCGIException("read", t);
        }
    }

    /// Writes exactly {@code len} bytes from {@code buf} starting at {@code off},
    /// looping internally if a single `write(2)` call accepts fewer.
    public static void write(int fd, byte[] buf, int off, int len) {
        try (Arena arena = Arena.ofConfined()) {
            var native_ = arena.allocate(len);
            MemorySegment.copy(buf, off, native_, ValueLayout.JAVA_BYTE, 0, len);
            long written = 0;
            while (written < len) {
                long n = (long) WRITE.invokeExact(fd, native_.asSlice(written), len - written);
                if (n < 0) {
                    throw new FCGIException("write failed");
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
            CLOSE.invokeExact(fd);
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
```

**Note for implementer:** `Linker.Option.captureStateLayout()`/`captureCallState("errno")` is the JDK 22+ FFM API for reading `errno` after a downcall. Before wiring this into the real shim, write a 5-line throwaway `jshell` or scratch-test snippet that calls `getsockname` on a deliberately-wrong fd (e.g. `Integer.MAX_VALUE`) and prints the captured errno, to confirm the exact method names compile against the JDK 25 toolchain this project uses (`java -version` reported `openjdk 25.0.2`) before trusting the code above verbatim.

- [ ] **Step 5: Run the tests and fix until green**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.posix.PosixTest"`
Expected: PASS — all 4 tests green.

- [ ] **Step 6: Commit**

```bash
git checkout -b feature/pure-java-fcgi-protocol
git add fcgi/src/main/java/red/ethel/fcgi/core/posix fcgi/src/test/java/red/ethel/fcgi/core/posix
git commit -m "Add minimal POSIX FFM shim to replace libfcgi accept/read/write"
```

---

## Chunk 2: FastCGI protocol primitives

**Files:**
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/protocol/RecordType.java`
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/protocol/RecordHeader.java`
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/protocol/NameValuePairs.java`
- Test: `fcgi/src/test/java/red/ethel/fcgi/core/protocol/RecordHeaderTest.java`
- Test: `fcgi/src/test/java/red/ethel/fcgi/core/protocol/NameValuePairsTest.java`

Pure data/algorithm code, no I/O, no native calls — the part of the original `fcgiapp.c` that's mechanically portable. Fully unit-testable in isolation.

- [ ] **Step 1: Write the failing test for `NameValuePairs`**

Create `fcgi/src/test/java/red/ethel/fcgi/core/protocol/NameValuePairsTest.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import static com.google.common.truth.Truth.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameValuePairsTest {
    @Test
    void decodesASingleShortNameAndValue() {
        // 1-byte lengths: name "A" (len 1), value "BB" (len 2)
        byte[] block = {1, 2, 'A', 'B', 'B'};
        assertThat(NameValuePairs.decode(block)).containsExactly("A", "BB");
    }

    @Test
    void decodesMultiplePairs() {
        byte[] block = {1, 1, 'A', 'X', 2, 1, 'B', 'B', 'Y'};
        assertThat(NameValuePairs.decode(block)).containsExactly("A", "X", "BB", "Y");
    }

    @Test
    void decodesALengthOf128OrMoreUsingTheFourByteForm() {
        String longValue = "v".repeat(200);
        var out = new java.io.ByteArrayOutputStream();
        out.write(1); // name length = 1
        // value length = 200, encoded as 4 bytes with high bit set on the first
        out.write(0x80 | ((200 >> 24) & 0x7F));
        out.write((200 >> 16) & 0xFF);
        out.write((200 >> 8) & 0xFF);
        out.write(200 & 0xFF);
        out.write('N');
        out.write(longValue.getBytes(), 0, longValue.length());
        assertThat(NameValuePairs.decode(out.toByteArray())).containsExactly("N", longValue);
    }

    @Test
    void encodeThenDecodeRoundTrips() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("REQUEST_METHOD", "GET");
        pairs.put("PATH_INFO", "v".repeat(150)); // forces the 4-byte length form
        byte[] encoded = NameValuePairs.encode(pairs);
        assertThat(NameValuePairs.decode(encoded)).containsExactlyEntriesIn(pairs);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.protocol.NameValuePairsTest"`
Expected: FAIL — `NameValuePairs` does not exist.

- [ ] **Step 3: Implement `NameValuePairs`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/protocol/NameValuePairs.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/// Codec for the FCGI_PARAMS name-value pair wire format: each name and value
/// is preceded by a length that is 1 byte if < 128, or a 4-byte big-endian
/// length (high bit set on the first byte) otherwise.
public final class NameValuePairs {
    private NameValuePairs() {}

    public static Map<String, String> decode(byte[] block) {
        var result = new LinkedHashMap<String, String>();
        int pos = 0;
        while (pos < block.length) {
            int[] nameLen = new int[1];
            pos = readLength(block, pos, nameLen);
            int[] valueLen = new int[1];
            pos = readLength(block, pos, valueLen);
            String name = new String(block, pos, nameLen[0], StandardCharsets.UTF_8);
            pos += nameLen[0];
            String value = new String(block, pos, valueLen[0], StandardCharsets.UTF_8);
            pos += valueLen[0];
            result.put(name, value);
        }
        return result;
    }

    private static int readLength(byte[] block, int pos, int[] out) {
        int first = block[pos] & 0xFF;
        if ((first & 0x80) == 0) {
            out[0] = first;
            return pos + 1;
        }
        out[0] = ((first & 0x7F) << 24)
                | ((block[pos + 1] & 0xFF) << 16)
                | ((block[pos + 2] & 0xFF) << 8)
                | (block[pos + 3] & 0xFF);
        return pos + 4;
    }

    public static byte[] encode(Map<String, String> pairs) {
        var out = new ByteArrayOutputStream();
        for (var entry : pairs.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
            writeLength(out, name.length);
            writeLength(out, value.length);
            out.write(name, 0, name.length);
            out.write(value, 0, value.length);
        }
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
        } else {
            out.write(0x80 | ((length >> 24) & 0x7F));
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.protocol.NameValuePairsTest"`
Expected: PASS.

- [ ] **Step 5: Write the failing test for `RecordHeader`/`RecordType`**

Create `fcgi/src/test/java/red/ethel/fcgi/core/protocol/RecordHeaderTest.java`:

```java
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
        assertThrows(
                IllegalArgumentException.class,
                () -> RecordHeader.readFrom(new ByteArrayInputStream(bytes)));
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(type, runnable);
    }

    private interface ThrowingRunnable extends org.junit.jupiter.api.function.Executable {}
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.protocol.RecordHeaderTest"`
Expected: FAIL — `RecordType`/`RecordHeader` do not exist.

- [ ] **Step 7: Implement `RecordType` and `RecordHeader`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/protocol/RecordType.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

/// The FastCGI record types this implementation needs (FCGI_GET_VALUES and
/// FCGI_UNKNOWN_TYPE management records are intentionally not handled - see
/// FcgiConnection's class doc for why that's an accepted limitation here).
public enum RecordType {
    BEGIN_REQUEST(1),
    ABORT_REQUEST(2),
    END_REQUEST(3),
    PARAMS(4),
    STDIN(5),
    STDOUT(6),
    STDERR(7),
    DATA(8),
    GET_VALUES(9),
    GET_VALUES_RESULT(10),
    UNKNOWN_TYPE(11);

    public final int code;

    RecordType(int code) {
        this.code = code;
    }

    public static RecordType fromCode(int code) {
        for (var type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown FastCGI record type: " + code);
    }
}
```

Create `fcgi/src/main/java/red/ethel/fcgi/core/protocol/RecordHeader.java`:

```java
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
            0
        });
    }
}
```

- [ ] **Step 8: Run the tests and confirm they pass**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.protocol.RecordHeaderTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 9: Commit**

```bash
git add fcgi/src/main/java/red/ethel/fcgi/core/protocol fcgi/src/test/java/red/ethel/fcgi/core/protocol
git commit -m "Add pure-Java FastCGI record header and name-value pair codecs"
```

---

## Chunk 3: Connection orchestration

**Files:**
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/protocol/FcgiConnection.java`
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/protocol/FcgiStdinInputStream.java`
- Create: `fcgi/src/main/java/red/ethel/fcgi/core/protocol/FcgiStdoutOutputStream.java`
- Test: `fcgi/src/test/java/red/ethel/fcgi/core/protocol/FcgiConnectionTest.java`

This is the piece that replaces `FCGX_InitRequest`/`FCGX_Accept_r`'s request-setup behaviour and the `FCGXInputStream`/`FCGXOutputStream` nested classes from `FCGINative.java`. It operates on plain `InputStream`/`OutputStream`, so it's testable entirely in memory with hand-built byte sequences — no sockets, no native calls, no Chunk 1 dependency at test time (Chunk 4 is what plugs `Posix`'s fd streams in as the real transport).

Scope decision, stated explicitly (not a TODO): FCGI_GET_VALUES (capability negotiation) and FCGI_ABORT_REQUEST are not handled. The current production deployment (DreamHost via `mod_fcgid`) doesn't send these, and `libfcgi` itself only multiplexes one request per connection — this implementation matches that constraint rather than adding speculative support.

- [ ] **Step 1: Write the failing test for reading a full request header**

Create `fcgi/src/test/java/red/ethel/fcgi/core/protocol/FcgiConnectionTest.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.protocol;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.protocol.FcgiConnectionTest"`
Expected: FAIL — `FcgiConnection` does not exist, and its constructor signature (`InputStream, OutputStream`) is referenced by the test, so this also pins the API the implementation must expose.

- [ ] **Step 3: Implement `FcgiStdinInputStream`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/protocol/FcgiStdinInputStream.java`:

```java
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
```

- [ ] **Step 4: Implement `FcgiStdoutOutputStream`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/protocol/FcgiStdoutOutputStream.java`:

```java
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
```

- [ ] **Step 5: Implement `FcgiConnection`**

Create `fcgi/src/main/java/red/ethel/fcgi/core/protocol/FcgiConnection.java`:

```java
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

        return NameValuePairs.decode(paramsBytes.toByteArray());
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
```

- [ ] **Step 6: Run the tests and fix until green**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.protocol.FcgiConnectionTest"`
Expected: PASS — all 3 tests green.

- [ ] **Step 7: Commit**

```bash
git add fcgi/src/main/java/red/ethel/fcgi/core/protocol fcgi/src/test/java/red/ethel/fcgi/core/protocol/FcgiConnectionTest.java
git commit -m "Add FcgiConnection: pure-Java request/response orchestration"
```

---

## Chunk 4: Wire it into FcgiService, remove FCGINative

**Files:**
- Modify: `fcgi/src/main/java/red/ethel/fcgi/core/FcgiService.java`
- Delete: `fcgi/src/main/java/red/ethel/fcgi/core/FCGINative.java`
- Modify: `fcgi/src/main/resources/META-INF/native-image/red/ethel/fcgi.core/reachability-metadata.json`
- Modify: `README.md`
- Test: `fcgi/src/test/java/red/ethel/fcgi/core/FcgiServiceIntegrationTest.java`

This chunk makes the swap real: `FcgiService` stops depending on `FCGINative`/`libfcgi` and starts depending on `Posix` + `FcgiConnection`. The integration test proves the whole replacement end-to-end over a real OS socket — not just at the unit level — by acting as a hand-rolled FastCGI client against a real `FcgiService` instance.

- [ ] **Step 1: Write the failing end-to-end integration test**

Create `fcgi/src/test/java/red/ethel/fcgi/core/FcgiServiceIntegrationTest.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import red.ethel.fcgi.core.posix.Posix;
import red.ethel.fcgi.core.protocol.NameValuePairs;
import red.ethel.fcgi.core.protocol.RecordHeader;
import red.ethel.fcgi.core.protocol.RecordType;

/// Drives a real FcgiService instance over an actual loopback TCP socket,
/// acting as the FastCGI client (the role Apache mod_fcgid normally plays)
/// to prove the full accept -> protocol -> handler -> response path works
/// without libfcgi.
class FcgiServiceIntegrationTest {
    @Test
    void servesARequestEndToEndOverARealSocket() throws Exception {
        int port = findFreePort();
        int listenFd = red.ethel.fcgi.core.posix.PosixTestSupportBridge.listenOn(port);

        var service = FcgiServiceTestFactory.createOnFd(listenFd);
        var serverThread = new Thread(() -> service.serve(exchange -> {
            try {
                exchange.out().write("hello from handler".getBytes());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port)) {
            OutputStream clientOut = client.getOutputStream();
            InputStream clientIn = client.getInputStream();

            writeBeginRequest(clientOut);
            var params = new LinkedHashMap<String, String>();
            params.put("REQUEST_METHOD", "GET");
            writeParamsRecord(clientOut, NameValuePairs.encode(params));
            writeParamsRecord(clientOut, new byte[0]);
            writeStdinRecord(clientOut, new byte[0]);

            var stdout = RecordHeader.readFrom(clientIn);
            assertThat(stdout.type()).isEqualTo(RecordType.STDOUT);
            byte[] body = clientIn.readNBytes(stdout.contentLength());
            assertThat(new String(body)).isEqualTo("hello from handler");
        }
    }

    private static int findFreePort() throws IOException {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void writeBeginRequest(OutputStream out) throws IOException {
        byte[] body = {0, 1, 0, 0, 0, 0, 0, 0}; // role = FCGI_RESPONDER
        new RecordHeader(1, RecordType.BEGIN_REQUEST, 1, body.length, 0).writeTo(out);
        out.write(body);
    }

    private static void writeParamsRecord(OutputStream out, byte[] content) throws IOException {
        new RecordHeader(1, RecordType.PARAMS, 1, content.length, 0).writeTo(out);
        out.write(content);
    }

    private static void writeStdinRecord(OutputStream out, byte[] content) throws IOException {
        new RecordHeader(1, RecordType.STDIN, 1, content.length, 0).writeTo(out);
        out.write(content);
    }
}
```

This test needs two small test-support seams that don't exist yet:
- `Posix`'s test-only `socket`/`bind`/`listen` helper (`PosixTestSupport.listenOn`) is package-private in the `posix` package (Chunk 1) and this test lives in package `red.ethel.fcgi.core` — add a one-line public bridge `red.ethel.fcgi.core.posix.PosixTestSupportBridge` (test source, same package as `PosixTestSupport`) that just calls `PosixTestSupport.listenOn(port)`, so the integration test can reach it without making production-irrelevant socket setup part of the public `Posix` API.
- `FcgiService.create()` always reads fd 0; tests need to target an arbitrary listening fd. Add a package-visible-for-test factory: `FcgiServiceTestFactory` in `red.ethel.fcgi.core` (test source) that constructs `FcgiService` directly against a given fd, bypassing `create()`'s fd-0/CGI-detection logic. This requires `FcgiService`'s constructor to accept a listen fd (see Step 3) rather than hardcoding fd 0.

Create `fcgi/src/test/java/red/ethel/fcgi/core/posix/PosixTestSupportBridge.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core.posix;

/// Test-only bridge exposing PosixTestSupport's package-private socket setup
/// to integration tests in other packages.
public final class PosixTestSupportBridge {
    private PosixTestSupportBridge() {}

    public static int listenOn(int port) {
        return PosixTestSupport.listenOn(port);
    }
}
```

Create `fcgi/src/test/java/red/ethel/fcgi/core/FcgiServiceTestFactory.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

/// Test-only entry point that builds an FcgiService against an arbitrary
/// already-listening fd, bypassing the fd-0/CGI-detection logic in create().
public final class FcgiServiceTestFactory {
    private FcgiServiceTestFactory() {}

    public static FcgiService createOnFd(int listenFd) {
        return FcgiService.forListenFd(listenFd);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.FcgiServiceIntegrationTest"`
Expected: FAIL — `FcgiService.forListenFd` doesn't exist yet.

- [ ] **Step 3: Rewrite `FcgiService`**

Replace the full contents of `fcgi/src/main/java/red/ethel/fcgi/core/FcgiService.java`:

```java
/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.ethel.fcgi.core.posix.Posix;
import red.ethel.fcgi.core.protocol.FcgiConnection;

public final class FcgiService extends BaseService implements Service {
    private static final Logger LOGGER = LoggerFactory.getLogger(FcgiService.class);
    private static final int STDIN_FD = 0;

    private final int listenFd;

    private FcgiService(int listenFd) {
        this.listenFd = listenFd;
    }

    public static Service create() {
        if (!Posix.isSocket(STDIN_FD)) {
            return new CgiService();
        }
        return new FcgiService(STDIN_FD);
    }

    /// Test-only seam: package-visible so FcgiServiceTestFactory (test source)
    /// can target an arbitrary listening fd instead of the real fd 0.
    static FcgiService forListenFd(int listenFd) {
        return new FcgiService(listenFd);
    }

    @Override
    public void serve(Handler handler) {
        LOGGER.debug("FcgiService.serve");
        while (true) {
            int clientFd = Posix.accept(listenFd);
            LOGGER.debug("accepted fd {}", clientFd);
            executor.execute(() -> handleConnection(clientFd, handler));
        }
    }

    private void handleConnection(int clientFd, Handler handler) {
        try {
            var connection = new FcgiConnection(Posix.inputStream(clientFd), Posix.outputStream(clientFd));
            var env = connection.readRequestHeader();
            try (var out = connection.stdout()) {
                handler.handle(new FCGIExchange(env, connection.stdin(), out));
            }
            connection.finish(0);
        } catch (Exception e) {
            // Catches both IOException (protocol/transport failures) and any
            // unchecked exception from the application handler, matching the
            // previous implementation's catch (Throwable e) in its worker body.
            LOGGER.error("Exception handling FastCGI connection", e);
        } finally {
            Posix.close(clientFd);
        }
    }

    @Override
    public void close() {
        // nothing to release: Posix holds no per-process resources to close
    }

    @Override
    protected Executor defaultExecutor() {
        // Platform threads, not virtual: accept()/read()/write() are blocking
        // FFM downcalls, which always pin their carrier for the call's
        // duration, regardless of which native library they call into. A
        // virtual-thread carrier pool would starve under concurrent
        // connections the same way a single perpetually-blocked accept()
        // listener once did - one platform thread per connection avoids
        // that because each one is already its own carrier.
        ThreadFactory factory = Thread.ofPlatform().name("worker", 1).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
```

- [ ] **Step 4: Delete `FCGINative.java`**

```bash
git rm fcgi/src/main/java/red/ethel/fcgi/core/FCGINative.java
```

- [ ] **Step 5: Run the integration test and fix until green**

Run: `./gradlew :fcgi:test --tests "red.ethel.fcgi.core.FcgiServiceIntegrationTest"`
Expected: PASS.

If the test hangs: the most likely cause is the `serve()` loop's `accept()` blocking forever because the client wrote to the wrong port, or a record was framed wrong (check `paddingLength` is 0 everywhere in the test's hand-written records, since the test doesn't add padding). Add a JUnit `@Timeout(10)` annotation to the test method if a hang needs to fail loudly instead of stalling CI.

- [ ] **Step 6: Run the full `fcgi` module test suite**

Run: `./gradlew :fcgi:test`
Expected: PASS — every test from Chunks 1-4 green together.

- [ ] **Step 7: Update native-image reachability metadata**

The existing `fcgi/src/main/resources/META-INF/native-image/red/ethel/fcgi.core/reachability-metadata.json` lists downcall signatures for the 9 deleted `libfcgi` functions. Replace its contents to describe the new (smaller) set of downcalls in `Posix`:

```json
{
  "foreign": {
    "downcalls": [
      { "returnType": "int", "parameterTypes": ["int", "long", "long"] },
      { "returnType": "long", "parameterTypes": ["int", "long", "long"] },
      { "returnType": "int", "parameterTypes": ["int"] },
      { "returnType": "int", "parameterTypes": ["long", "int", "long", "long"] }
    ]
  }
}
```

Note for implementer: cross-check this against whatever `nativeCompile` actually reports as missing (GraalVM's agent can regenerate this file automatically: run the `fcgi` module's tests under the native-image tracing agent, or simply attempt `./gradlew :fcgi:nativeCompile` if that task exists standalone, and adjust entries to match any `MissingReflectionRegistrationError`/foreign-call errors raised). Don't hand-guess the final shape if the build gives a concrete answer.

- [ ] **Step 8: Verify the GraalVM native build for one real app**

Run: `./gradlew :test-httpserver:nativeCompile :test-httpserver:integrationTest`
Expected: PASS, with no dependency on `libfcgi.so` — confirm by checking the command does **not** fail with an `UnsatisfiedLinkError` or missing-symbol error referencing `FCGX_*`.

- [ ] **Step 9: Update README**

In `README.md`, remove the `libfcgi` system requirement:

Change:
```markdown
## Requirements

- Java 25+
- `libfcgi` installed (e.g. `apt install libfcgi-dev` on Debian/Ubuntu)
```
To:
```markdown
## Requirements

- Java 25+
```

Also update the project description line (currently `"...using the Foreign Function & Memory (FFM) API to wrap the native libfcgi library."`) to reflect the new architecture, e.g. `"...using the Foreign Function & Memory (FFM) API for a minimal POSIX socket shim, with the FastCGI protocol itself implemented in pure Java."`, and update the `fcgi` module's row in the Modules table similarly (currently `"FFM bindings for libfcgi, Service implementations..."`).

- [ ] **Step 10: Run the full build**

Run: `./gradlew build`
Expected: PASS — compiles, tests, and `spotlessCheck` all succeed across every module (`httpserver`, `test-httpserver`, `test-jex`, `test-mysql`, `test-avaje` all depend on `fcgi` transitively and must keep working unmodified, since `Service`/`Handler`/`FCGIExchange`/`FcgiService.create()` are unchanged).

- [ ] **Step 11: Commit**

```bash
git add fcgi/src/main/java/red/ethel/fcgi/core/FcgiService.java \
        fcgi/src/test/java/red/ethel/fcgi/core/FcgiServiceIntegrationTest.java \
        fcgi/src/test/java/red/ethel/fcgi/core/FcgiServiceTestFactory.java \
        fcgi/src/test/java/red/ethel/fcgi/core/posix/PosixTestSupportBridge.java \
        fcgi/src/main/resources/META-INF/native-image/red/ethel/fcgi.core/reachability-metadata.json \
        README.md
git commit -m "Replace libfcgi FFM wrapper with pure-Java protocol + minimal POSIX shim"
```

---

## Final verification (whole branch)

- [ ] `./gradlew build` passes from a clean checkout (`./gradlew clean build`)
- [ ] `grep -r libfcgi fcgi/src` returns nothing (confirm the dependency is fully gone from source, not just unused)
- [ ] `./gradlew :test-httpserver:integrationTest :test-jex:integrationTest :test-mysql:integrationTest :test-avaje:integrationTest` all pass unmodified
- [ ] Manually deploy `test-httpserver` to the DreamHost target (same process used for the earlier carrier-pinning fix) and confirm cold starts still work without `libfcgi-dev` installed on the host — this is the real-world proof the hybrid design holds up outside the dev sandbox.
- [ ] Once verified, follow `superpowers:finishing-a-development-branch` to decide on merge/PR for `feature/pure-java-fcgi-protocol`.
