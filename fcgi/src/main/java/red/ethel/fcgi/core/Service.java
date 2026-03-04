/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;

public sealed interface Service extends AutoCloseable permits BaseService, CgiService, FcgiService {
    void serve(Handler handler);

    void setExecutor(Executor executor);

    Executor getExecutor();
}
