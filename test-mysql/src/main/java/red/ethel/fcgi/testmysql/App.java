/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testmysql;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    static void main() {
        try {
            LOGGER.info("Starting test-mysql app");

            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + System.getenv("MYSQL_HOST") + "/" + System.getenv("MYSQL_DATABASE"));
            config.setUsername(System.getenv("MYSQL_USERNAME"));
            config.setPassword(System.getenv("MYSQL_PASSWORD"));
            config.setMaximumPoolSize(5);
            config.setConnectionTimeout(10_000);

            var dataSource = new HikariDataSource(config);

            var server = HttpServer.create();
            server.createContext("/db/counters", new CountersHandler(dataSource));
            server.start();

            Runtime.getRuntime()
                    .addShutdownHook(Thread.ofVirtual().name("shutdown").unstarted(() -> {
                        server.stop(20);
                        dataSource.close();
                    }));

        } catch (IOException e) {
            LOGGER.error("Failed to start server", e);
            throw new RuntimeException(e);
        }
    }
}
