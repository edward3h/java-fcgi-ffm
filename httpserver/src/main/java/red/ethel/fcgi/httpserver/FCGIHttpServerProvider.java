/* (C) Edward Harman 2026 */
package red.ethel.fcgi.httpserver;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import io.avaje.spi.ServiceProvider;
import java.io.IOException;
import java.net.InetSocketAddress;

/// Provides an implementation of HttpServer based on FCGI or CGI.
@ServiceProvider
public class FCGIHttpServerProvider extends HttpServerProvider {
    /// Same as createHttpsServer - there is only one implementation
    ///
    /// @param addr ignored - not supported
    /// @param backlog ignored - not supported
    @Override
    public HttpServer createHttpServer(InetSocketAddress addr, int backlog) throws IOException {
        return createHttpsServer(addr, backlog);
    }

    /// Does not actually handle https itself, but may be receiving https requests because the upstream webserver layer
    /// handles it.
    /// @param addr ignored - not supported
    /// @param backlog ignored - not supported
    @Override
    public HttpsServer createHttpsServer(InetSocketAddress addr, int backlog) {
        return new FCGIHttpsServer();
    }
}
