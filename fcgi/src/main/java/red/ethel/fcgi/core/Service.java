/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;

public sealed interface Service extends AutoCloseable permits BaseService, CgiService, FcgiService {
    void serve(Handler handler);

    /**
     * No-op. The executor is fixed internally to match the threading model each
     * implementation relies on and cannot be overridden.
     */
    void setExecutor(Executor executor);

    Executor getExecutor();
}
