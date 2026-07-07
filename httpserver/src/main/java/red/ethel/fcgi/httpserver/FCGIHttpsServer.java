/* (C) Edward Harman 2026 */
package red.ethel.fcgi.httpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.ethel.fcgi.core.FCGIExchange;
import red.ethel.fcgi.core.FcgiService;
import red.ethel.fcgi.core.Handler;
import red.ethel.fcgi.core.Service;

final class FCGIHttpsServer extends HttpsServer implements Handler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FCGIHttpsServer.class);

    private final Service coreService = FcgiService.create();
    private final ContextList contextList = new ContextList();
    private @Nullable Thread serviceThread;

    @Override
    public void setHttpsConfigurator(HttpsConfigurator config) {
        // ignore - doesn't actually implement HTTPS
    }

    @Override
    public HttpsConfigurator getHttpsConfigurator() {
        return null;
    }

    @Override
    public void bind(InetSocketAddress addr, int backlog) throws IOException {
        // ignore - outside the control of this process
    }

    @Override
    public void start() {
        //        Thread thread;
        //        synchronized (this) {
        //            if (serviceThread != null) {
        //                throw new IllegalStateException("Server already started");
        //            }
        //            thread = serviceThread = Thread.ofVirtual().name("httpserver").unstarted(() ->
        // coreService.serve(this));
        //        }
        //        thread.start();
        serviceThread = Thread.currentThread();
        coreService.serve(this);
    }

    /**
     * No-op. Unlike the standard {@link com.sun.net.httpserver.HttpsServer}, the executor here
     * is fixed internally and cannot be overridden.
     */
    @Override
    public void setExecutor(Executor executor) {
        coreService.setExecutor(executor);
    }

    @Override
    public Executor getExecutor() {
        return coreService.getExecutor();
    }

    @Override
    public void stop(int delay) {
        LOGGER.debug("stop requested");
        Thread thread;
        synchronized (this) {
            if (serviceThread == null) {
                throw new IllegalStateException("Server not started");
            }
            thread = serviceThread;
        }
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(delay));
        } catch (InterruptedException _) {
            // it's fine, just give up
        }
        LOGGER.debug("stopped");
    }

    @Override
    public HttpContext createContext(String path, @Nullable HttpHandler handler) {
        return contextList.create(this, path, handler);
    }

    @Override
    public HttpContext createContext(String path) {
        return createContext(path, null);
    }

    @Override
    public void removeContext(String path) throws IllegalArgumentException {
        contextList.remove(path);
    }

    @Override
    public void removeContext(HttpContext context) {
        removeContext(context.getPath());
    }

    @Override
    public InetSocketAddress getAddress() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handle(FCGIExchange exchange) {
        LOGGER.debug("handle enter");
        var path = exchange.env().get("SCRIPT_URL");
        var context = path == null ? null : contextList.findContext(path);
        if (context != null && context.getHandler() != null) {
            try (var httpExchange = new FCGIHttpExchange(exchange, context)) {
                context.getHandler().handle(httpExchange);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            handleError(404, exchange);
        }
        LOGGER.debug("handle exit");
    }

    private void handleError(int statusCode, FCGIExchange exchange) {
        new PrintStream(exchange.out()).format("Status: %d\r\n\r\n", statusCode);
    }
}
