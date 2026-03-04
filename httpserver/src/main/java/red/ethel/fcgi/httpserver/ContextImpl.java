/* (C) Edward Harman 2026 */
package red.ethel.fcgi.httpserver;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

class ContextImpl extends HttpContext {
    private final FCGIHttpsServer server;
    private final String path;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<Filter> filters = new CopyOnWriteArrayList<>();
    private @Nullable HttpHandler handler;

    ContextImpl(FCGIHttpsServer server, String path, @Nullable HttpHandler handler) {
        this.server = server;
        this.path = path;
        this.handler = handler;
    }

    @Override
    public synchronized @Nullable HttpHandler getHandler() {
        return handler;
    }

    @Override
    public synchronized void setHandler(HttpHandler handler) {
        if (this.handler != null) {
            throw new IllegalStateException("handler already set");
        }
        this.handler = handler;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public HttpServer getServer() {
        return server;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public List<Filter> getFilters() {
        return filters;
    }

    @Override
    public @Nullable Authenticator setAuthenticator(@Nullable Authenticator auth) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Authenticator getAuthenticator() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ContextImpl context = (ContextImpl) o;
        return Objects.equals(path, context.path);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(path);
    }
}
