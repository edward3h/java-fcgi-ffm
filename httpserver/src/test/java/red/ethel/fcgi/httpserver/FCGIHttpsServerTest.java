/* (C) Edward Harman 2026 */
package red.ethel.fcgi.httpserver;

import static com.google.common.truth.Truth.assertThat;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import red.ethel.fcgi.core.FCGIExchange;

class FCGIHttpsServerTest {

    private static FCGIExchange exchange(String path) {
        var env = Map.of("SCRIPT_URL", path, "REQUEST_METHOD", "GET");
        return new FCGIExchange(env, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
    }

    private static Filter recordingFilter(String name, List<String> order) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                order.add(name);
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return name;
            }
        };
    }

    private static Filter shortCircuitFilter(int statusCode) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                exchange.sendResponseHeaders(statusCode, 0);
            }

            @Override
            public String description() {
                return "short-circuit";
            }
        };
    }

    @Test
    void filtersRunBeforeHandlerInRegistrationOrder() throws IOException {
        var order = new ArrayList<String>();
        var server = new FCGIHttpsServer();
        var context = server.createContext("/", ex -> {
            order.add("handler");
            ex.sendResponseHeaders(200, 0);
        });
        context.getFilters().add(recordingFilter("first", order));
        context.getFilters().add(recordingFilter("second", order));

        server.handle(exchange("/"));

        assertThat(order).containsExactly("first", "second", "handler").inOrder();
    }

    @Test
    void filterCanShortCircuitAndSkipHandler() throws IOException {
        var handlerCalled = new boolean[1];
        var server = new FCGIHttpsServer();
        var context = server.createContext("/", ex -> {
            handlerCalled[0] = true;
            ex.sendResponseHeaders(200, 0);
        });
        context.getFilters().add(shortCircuitFilter(403));

        server.handle(exchange("/"));

        assertThat(handlerCalled[0]).isFalse();
    }

    @Test
    void filterCanSetAttributeSeenByHandler() throws IOException {
        var seenValue = new Object[1];
        var server = new FCGIHttpsServer();
        var context = server.createContext("/", ex -> {
            seenValue[0] = ex.getAttribute("key");
            ex.sendResponseHeaders(200, 0);
        });
        context.getFilters().add(new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                exchange.setAttribute("key", "value");
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return "attribute-setter";
            }
        });

        server.handle(exchange("/"));

        assertThat(seenValue[0]).isEqualTo("value");
    }
}
