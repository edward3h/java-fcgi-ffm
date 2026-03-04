/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Wrapper for `fcgiapp.h`. See [archive of FastCGI](https://fastcgi-archives.github.io/).
///
/// Hand-written because I was running into issues with *jExtract*.
final class FCGINative implements AutoCloseable {
    public static final ValueLayout.OfByte C_CHAR =
            (ValueLayout.OfByte) Linker.nativeLinker().canonicalLayouts().get("char");
    public static final ValueLayout.OfInt C_INT =
            (ValueLayout.OfInt) Linker.nativeLinker().canonicalLayouts().get("int");
    public static final AddressLayout C_POINTER = ((AddressLayout)
                    Linker.nativeLinker().canonicalLayouts().get("void*"))
            .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, C_CHAR));
    private static final GroupLayout FCGX_Request_layout = MemoryLayout.structLayout(
                    C_INT.withName("requestId"),
                    C_INT.withName("role"),
                    C_POINTER.withName("in"),
                    C_POINTER.withName("out"),
                    C_POINTER.withName("err"),
                    C_POINTER.withName("envp"),
                    C_POINTER.withName("paramsPtr"),
                    C_INT.withName("ipcFd"),
                    C_INT.withName("isBeginProcessed"),
                    C_INT.withName("keepConnection"),
                    C_INT.withName("appStatus"),
                    C_INT.withName("nWriters"),
                    C_INT.withName("flags"),
                    C_INT.withName("listen_sock"),
                    C_INT.withName("detached"))
            .withName("FCGX_Request");
    public static final ValueLayout.OfLong C_LONG =
            (ValueLayout.OfLong) Linker.nativeLinker().canonicalLayouts().get("long");
    private static final Logger LOGGER = LoggerFactory.getLogger(FCGINative.class);
    private static final long MAX_STRING_BYTES = 8L * 1024;
    private final Arena libraryArena = Arena.ofAuto();
    // just make it work
    private final SymbolLookup symbolLookup = SymbolLookup.libraryLookup(
            Path.of("/usr/lib/x86_64-linux-gnu/libfcgi.so"), libraryArena); // TODO configurable library path
    private final MethodHandle FCGX_IsCGI_handle =
            Linker.nativeLinker().downcallHandle(symbolLookup.findOrThrow("FCGX_IsCGI"), FunctionDescriptor.of(C_INT));
    private final MethodHandle FCGX_Init_handle =
            Linker.nativeLinker().downcallHandle(symbolLookup.findOrThrow("FCGX_Init"), FunctionDescriptor.of(C_INT));
    private final MethodHandle FCGX_InitRequest_handle = Linker.nativeLinker()
            .downcallHandle(
                    symbolLookup.findOrThrow("FCGX_InitRequest"),
                    FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_INT));
    private final MethodHandle FCGX_Accept_r_handle = Linker.nativeLinker()
            .downcallHandle(symbolLookup.findOrThrow("FCGX_Accept_r"), FunctionDescriptor.of(C_INT, C_POINTER));
    private final MethodHandle FCGX_Finish_r_handle = Linker.nativeLinker()
            .downcallHandle(symbolLookup.findOrThrow("FCGX_Finish_r"), FunctionDescriptor.ofVoid(C_POINTER));
    private final MethodHandle FCGX_GetChar_handle = Linker.nativeLinker()
            .downcallHandle(symbolLookup.findOrThrow("FCGX_GetChar"), FunctionDescriptor.of(C_INT, C_POINTER));
    private final MethodHandle FCGX_GetStr_handle = Linker.nativeLinker()
            .downcallHandle(
                    symbolLookup.findOrThrow("FCGX_GetStr"), FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER));
    private final MethodHandle FCGX_PutChar_handle = Linker.nativeLinker()
            .downcallHandle(symbolLookup.findOrThrow("FCGX_PutChar"), FunctionDescriptor.of(C_INT, C_INT, C_POINTER));
    private final MethodHandle FCGX_PutStr_handle = Linker.nativeLinker()
            .downcallHandle(
                    symbolLookup.findOrThrow("FCGX_PutChar"),
                    FunctionDescriptor.of(C_INT, C_POINTER, C_INT, C_POINTER));
    private final MethodHandle FCGX_FFlush_handle = Linker.nativeLinker()
            .downcallHandle(symbolLookup.findOrThrow("FCGX_FFlush"), FunctionDescriptor.of(C_INT, C_POINTER));
    private final MethodHandle FCGX_FClose_handle = Linker.nativeLinker()
            .downcallHandle(symbolLookup.findOrThrow("FCGX_FClose"), FunctionDescriptor.of(C_INT, C_POINTER));

    FCGINative() {
        init();
    }

    /// I couldn't find an existing library method to handle this.
    ///
    /// @param envp is a **char. That is a null-terminated array of null-terminated strings.
    /// @return Environment variables as map
    static Map<String, String> readEnv(MemorySegment envp) {
        var strings = new ArrayList<String>();
        boolean done = false;
        long index = 0;
        while (!done && index < 100) {
            try {
                // nul address marks the end of the array
                var testNUL = envp.getAtIndex(C_LONG, index);
                if (testNUL == 0) {
                    done = true;
                } else {
                    // de-reference element
                    var element = envp.getAtIndex(ValueLayout.ADDRESS, index).reinterpret(MAX_STRING_BYTES);

                    String s = element.getString(0);
                    strings.add(s);
                    index++;
                }
            } catch (Throwable t) {
                LOGGER.debug("readEnv exception", t);
                done = true;
            }
        }
        // The strings are in the form of "key=value".
        var mutableResult = new HashMap<String, String>();
        for (var s : strings) {
            var kv = s.split("=", 2);
            if (kv.length == 1) {
                mutableResult.put(kv[0], "");
            } else if (kv.length > 1) {
                mutableResult.put(kv[0], kv[1]);
            }
        }
        return Map.copyOf(mutableResult);
    }

    public boolean isCGI() {
        var r = 1;
        try {
            r = (int) FCGX_IsCGI_handle.invokeExact();
        } catch (Throwable e) {
            throw new FCGIException("In FCGX_IsCGI", e);
        }
        return r > 0;
    }

    private void init() {
        invokeErrorReturningFunction("FCGX_Init", () -> (int) FCGX_Init_handle.invokeExact());
    }

    /// Support the typical C pattern where a function returns an int that is an error code.
    /// TODO better handling for the resulting errors.
    ///
    /// @param functionName Name of the function being invoked - used in error reporting.
    /// @param invokable    handle to invoke
    private void invokeErrorReturningFunction(String functionName, Invokable invokable) {
        int err;
        try {
            err = invokable.invoke();
        } catch (Throwable e) {
            throw new FCGIException("In " + functionName, e);
        }
        if (err != 0) {
            throw new FCGIException(functionName, err);
        }
    }

    /// Blocks until server receives a request. The caller is expected to close the request object when complete;
    /// typically with a try-with-resources.
    ///
    /// @return request object
    public FCGXRequest accept() {
        return new FCGXRequest();
    }

    @Override
    public void close() throws Exception {
        libraryArena.close();
    }

    private interface Invokable {
        int invoke() throws Throwable;
    }

    public class FCGXRequest implements AutoCloseable {
        private final Arena arena;
        private final MemorySegment structPointer;

        FCGXRequest() {
            // request is confined to a single thread
            arena = Arena.ofConfined();
            // allocate memory for the struct
            structPointer = arena.allocate(FCGX_Request_layout);
            // prepare the struct. Last two arguments have been "0, 0" in every example I looked at.
            invokeErrorReturningFunction(
                    "FCGX_InitRequest", () -> (int) FCGX_InitRequest_handle.invokeExact(structPointer, 0, 0));
            // perform accept - blocking
            invokeErrorReturningFunction("FCGX_Accept_r", () -> (int) FCGX_Accept_r_handle.invokeExact(structPointer));
        }

        public FCGIExchange exchange() {
            var envp = getPointerField("envp");
            var env = readEnv(envp);
            var in = new FCGXInputStream(getPointerField("in"));
            var out = new FCGXOutputStream(getPointerField("out"));
            return new FCGIExchange(env, in, out);
        }

        @Override
        public void close() throws Exception {
            try {
                FCGX_Finish_r_handle.invokeExact(structPointer);
            } catch (Throwable e) {
                throw new FCGIException("FCGX_Finish_r", e);
            } finally {
                arena.close();
            }
        }

        private MemorySegment getPointerField(String name) {
            return structPointer.get(
                    (AddressLayout) FCGX_Request_layout.select(groupElement(name)),
                    FCGX_Request_layout.byteOffset(groupElement(name)));
        }
    }

    private class FCGXOutputStream extends OutputStream {
        private final MemorySegment pointer;

        public FCGXOutputStream(MemorySegment pointer) {
            this.pointer = pointer;
        }

        @Override
        public void write(int b) throws IOException {
            try {
                FCGX_PutChar_handle.invokeExact(b, pointer);
            } catch (Throwable e) {
                throw new IOException(e);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            try (var arena = Arena.ofConfined()) {
                var charStar = arena.allocate(MemoryLayout.sequenceLayout(len, C_CHAR));
                MemorySegment.copy(b, off, charStar, C_CHAR, 0, len);
                var count = (int) FCGX_PutStr_handle.invokeExact(charStar, len, pointer);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                invokeErrorReturningFunction("FCGX_FFlush", () -> (int) FCGX_FFlush_handle.invokeExact(pointer));
            } catch (Throwable e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                invokeErrorReturningFunction("FCGX_FFlush", () -> (int) FCGX_FFlush_handle.invokeExact(pointer));
                invokeErrorReturningFunction("FCGX_FClose", () -> (int) FCGX_FClose_handle.invokeExact(pointer));
            } catch (Throwable e) {
                throw new IOException(e);
            }
        }
    }

    private class FCGXInputStream extends InputStream {
        private final MemorySegment pointer;

        public FCGXInputStream(MemorySegment pointer) {
            this.pointer = pointer;
        }

        @Override
        public int read() throws IOException {
            try {
                return (int) FCGX_GetChar_handle.invokeExact(pointer);
            } catch (Throwable e) {
                throw new IOException(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);

            try (var arena = Arena.ofConfined()) {
                var charStar = arena.allocate(MemoryLayout.sequenceLayout(len, C_CHAR));
                var count = (int) FCGX_GetStr_handle.invokeExact(charStar, len, pointer);
                MemorySegment.copy(charStar, C_CHAR, 0, b, off, count);
                return count;
            } catch (Throwable e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                invokeErrorReturningFunction("FCGX_FClose", () -> (int) FCGX_FClose_handle.invokeExact(pointer));
            } catch (Throwable e) {
                throw new IOException(e);
            }
        }
    }
}
