/* (C) Edward Harman 2026 */
package red.ethel.fcgi.httpserver;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.ethel.fcgi.core.FCGIExchange;

final class FCGIHttpExchange extends HttpExchange {
    private static final Logger LOGGER = LoggerFactory.getLogger(FCGIHttpExchange.class);

    private final FCGIExchange fcgiExchange;
    private final ContextImpl context;
    private InputStream useInput;
    private OutputStream useOutput;
    private final DeferredOutputStream deferredOutputStream;
    private final Map<String, Object> attributes = new HashMap<>();
    private int statusCode = -1;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new Headers();
    private boolean sentHeaders;

    public FCGIHttpExchange(FCGIExchange fcgiExchange, ContextImpl context) {
        this.fcgiExchange = fcgiExchange;
        this.context = context;
        this.useInput = fcgiExchange.in();
        this.useOutput = deferredOutputStream = new DeferredOutputStream();
        attributes.putAll(context.getAttributes());
        requestHeaders = headersFromEnv(fcgiExchange.env());
    }

    private Headers headersFromEnv(Map<String, String> env) {
        var transformed = env.entrySet().stream()
                .map(mapKey(s -> s.startsWith("HTTP_") ? s.substring(5) : s))
                .map(mapKey(s -> s.replace('_', '-')))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return Headers.of(transformed); // Headers does additional validation and normalization
    }

    private <K, V> Function<Map.Entry<K, V>, Map.Entry<K, V>> mapKey(Function<K, K> mappingFunction) {
        return entry -> Map.entry(mappingFunction.apply(entry.getKey()), entry.getValue());
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return URI.create(fcgiExchange.env().getOrDefault("REQUEST_URI", "/"));
    }

    @Override
    public String getRequestMethod() {
        return fcgiExchange.env().getOrDefault("REQUEST_METHOD", "GET").strip().toUpperCase();
    }

    @Override
    public HttpContext getHttpContext() {
        return context;
    }

    @Override
    public void close() {}

    @Override
    public InputStream getRequestBody() {
        return useInput;
    }

    @Override
    public OutputStream getResponseBody() {
        return deferredOutputStream;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
        if (sentHeaders) {
            throw new IllegalStateException("Already sent headers");
        }
        sentHeaders = true;
        statusCode = rCode;
        responseHeaders.put("Status", List.of(String.valueOf(statusCode)));
        LOGGER.debug("headers {}", responseHeaders);
        var out = fcgiExchange.out();

        responseHeaders.forEach((k, v) -> {
            try {
                out.write(k.getBytes(StandardCharsets.UTF_8));
                out.write(": ".getBytes(StandardCharsets.UTF_8));
                out.write(v.getFirst().getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOGGER.debug("printed headers");
        deferredOutputStream.setDelegate(out);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        var addr = fcgiExchange.env().get("REMOTE_ADDR");
        var port = fcgiExchange.env().get("REMOTE_PORT");
        try {
            return new InetSocketAddress(InetAddress.getByName(addr), Integer.parseInt(port));
        } catch (UnknownHostException e) {
            // unexpected when addr is an IP address already
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getResponseCode() {
        return statusCode;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        var addr = fcgiExchange.env().get("SERVER_ADDR");
        var port = fcgiExchange.env().get("SERVER_PORT");
        try {
            return new InetSocketAddress(InetAddress.getByName(addr), Integer.parseInt(port));
        } catch (UnknownHostException e) {
            // unexpected when addr is an IP address already
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getProtocol() {
        return fcgiExchange.env().get("SERVER_PROTOCOL");
    }

    @Override
    public @Nullable Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, @Nullable Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void setStreams(@Nullable InputStream i, @Nullable OutputStream o) {
        if (i != null) {
            useInput = i;
        }
        if (o != null) {
            useOutput = o;
        }
    }

    @Override
    public HttpPrincipal getPrincipal() {
        throw new UnsupportedOperationException();
    }

    private static class DeferredOutputStream extends OutputStream {
        private void setDelegate(@Nullable OutputStream delegate) {
            this.delegate = delegate;
        }

        @Nullable OutputStream delegate;

        @Override
        public void write(int b) throws IOException {
            if (delegate == null) {
                throw new IllegalStateException("Cannot write response before calling sendResponseHeaders");
            }
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (delegate == null) {
                throw new IllegalStateException("Cannot write response before calling sendResponseHeaders");
            }
            delegate.write(b, off, len);
        }

        @Override
        public void write(byte[] b) throws IOException {
            if (delegate == null) {
                throw new IllegalStateException("Cannot write response before calling sendResponseHeaders");
            }
            delegate.write(b);
        }

        @Override
        public void close() throws IOException {
            if (delegate == null) {
                throw new IllegalStateException("Cannot write response before calling sendResponseHeaders");
            }
            delegate.close();
        }

        //        @Override
        //        public void flush() throws IOException {
        //            if (delegate == null) {
        //                throw new IllegalStateException("Cannot write response before calling sendResponseHeaders");
        //            }
        //            delegate.flush();
        //        }
    }
}
