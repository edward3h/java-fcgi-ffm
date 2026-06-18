/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testavaje;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import jakarta.inject.Named;

@Factory
public class DataSourceFactory {

    // returns the concrete HikariDataSource (not the javax.sql.DataSource interface)
    // because avaje-inject 12.6 generates the destroyMethod call against the
    // declared return type, and DataSource itself has no close() method
    @Bean(destroyMethod = "close")
    @Named("default")
    HikariDataSource dataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + System.getenv("MYSQL_HOST") + "/" + System.getenv("MYSQL_DATABASE"));
        config.setUsername(System.getenv("MYSQL_USERNAME"));
        config.setPassword(System.getenv("MYSQL_PASSWORD"));
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }
}
