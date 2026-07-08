/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testhttpserver;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Formatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    static void main() {
        try {
            LOGGER.info("Starting");
            var server = HttpServer.create();
            server.createContext("/", new MyHandler("root"));
            server.createContext("/foo", new MyHandler("foo"))
                    .getFilters()
                    .add(Filter.beforeHandler("add-header", exchange -> exchange.getResponseHeaders()
                            .add("X-Filter", "applied")));
            server.createContext("/blocked", new MyHandler("blocked"))
                    .getFilters()
                    .add(new Filter() {
                        @Override
                        public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                            exchange.sendResponseHeaders(403, -1);
                        }

                        @Override
                        public String description() {
                            return "block";
                        }
                    });
            server.start();
            Runtime.getRuntime()
                    .addShutdownHook(Thread.ofVirtual().name("shutdown").unstarted(() -> server.stop(20)));

        } catch (IOException e) {
            LOGGER.error("Exception", e);
            throw new RuntimeException(e);
        }
    }

    private static class MyHandler implements HttpHandler {
        private final String name;

        public MyHandler(String name) {
            this.name = name;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            LOGGER.debug("handle enter");
            LOGGER.debug("User-Agent: {}", exchange.getRequestHeaders().getFirst("User-agent"));
            LOGGER.debug("Remote: {}", exchange.getRemoteAddress());
            var method = exchange.getRequestMethod();
            var uri = exchange.getRequestURI();
            var body = "";
            if ("POST".equals(method)) {
                try (var input = exchange.getRequestBody();
                        var reader = new InputStreamReader(input)) {
                    body = reader.readAllAsString();
                }
            }
            exchange.getResponseHeaders().add("Content-type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            LOGGER.debug("sent headers");
            try (var output = exchange.getResponseBody();
                    var formatter = new Formatter(output)) {
                formatter.format("""
                        Handler %s
                        ==============
                        Method %s
                        URI %s
                        ==============
                        %s
                        ==============
                        """, name, method, uri, body);
                //                formatter.flush();
            }
            LOGGER.debug("handle exit");
        }
    }
}
