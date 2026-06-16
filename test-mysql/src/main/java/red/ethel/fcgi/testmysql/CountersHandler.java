/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testmysql;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CountersHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CountersHandler.class);
    private final HikariDataSource dataSource;

    CountersHandler(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        LOGGER.debug("CountersHandler.handle method={}", exchange.getRequestMethod());
        switch (exchange.getRequestMethod()) {
            case "GET" -> handleGet(exchange);
            case "POST" -> handlePost(exchange);
            default -> sendText(exchange, 405, "Method Not Allowed");
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT name, value FROM counter ORDER BY name")) {
            var lines = new ArrayList<String>();
            while (rs.next()) {
                lines.add(rs.getString("name") + "=" + rs.getInt("value"));
            }
            sendText(exchange, 200, String.join("\n", lines));
        } catch (SQLException e) {
            LOGGER.error("Database error in GET /db/counters", e);
            sendText(exchange, 500, "Error: " + e.getMessage());
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String name;
        try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            name = reader.readAllAsString().strip();
        }
        try (var conn = dataSource.getConnection();
                var stmt = conn.prepareStatement("UPDATE counter SET value = value + 1 WHERE name = ?")) {
            stmt.setString(1, name);
            var updated = stmt.executeUpdate();
            if (updated == 0) {
                sendText(exchange, 404, "Not found: " + name);
            } else {
                sendText(exchange, 200, "Updated: " + name);
            }
        } catch (SQLException e) {
            LOGGER.error("Database error in POST /db/counters", e);
            sendText(exchange, 500, "Error: " + e.getMessage());
        }
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
