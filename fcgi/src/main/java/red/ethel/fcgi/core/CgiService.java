/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CgiService extends BaseService implements Service {
    private static final Logger LOGGER = LoggerFactory.getLogger(CgiService.class);

    @Override
    public void serve(Handler handler) {
        executor.execute(() -> {
            LOGGER.debug("CgiService.serve");
            handler.handle(new FCGIExchange(System.getenv(), System.in, System.out));
        });
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    protected Executor defaultExecutor() {
        // run in same thread
        return Runnable::run;
    }
}
