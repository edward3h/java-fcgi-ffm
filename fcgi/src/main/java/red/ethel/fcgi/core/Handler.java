/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

/// The client of FCGI must implement this interface
@FunctionalInterface
public interface Handler {
    void handle(FCGIExchange exchange);
}
