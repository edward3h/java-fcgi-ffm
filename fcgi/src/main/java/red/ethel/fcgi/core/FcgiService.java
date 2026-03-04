/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FcgiService extends BaseService implements Service {
    private static final Logger LOGGER = LoggerFactory.getLogger(FcgiService.class);

    private final FCGINative nativeWrapper;

    private FcgiService(FCGINative nativeWrapper) {
        this.nativeWrapper = nativeWrapper;
    }

    public static Service create() {
        var wrapper = new FCGINative();
        if (wrapper.isCGI()) {
            try {
                wrapper.close();
            } catch (Exception _) {
                // ignore
            }
            return new CgiService();
        }
        return new FcgiService(wrapper);
    }

    @Override
    public void serve(Handler handler) {
        LOGGER.debug("FcgiService.serve");
        Semaphore acceptOne = new Semaphore(1);

        try {

            while (true) {
                acceptOne.acquire();
                executor.execute(() -> {
                    LOGGER.debug("Worker.run enter");
                    try (var request = nativeWrapper.accept()) {
                        acceptOne.release();
                        LOGGER.debug("handle enter");
                        handler.handle(request.exchange());
                        LOGGER.debug("handle exit");
                    } catch (Throwable e) {
                        LOGGER.debug("Exception in worker thread", e);
                    } finally {
                        LOGGER.debug("Worker.run exit");
                    }
                });
            }
        } catch (InterruptedException _) {
            LOGGER.debug("interrupted");
        }
    }

    @Override
    public void close() throws Exception {
        nativeWrapper.close();
    }

    @Override
    protected Executor defaultExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("worker", 1).factory();
        return factory::newThread;
    }
}
