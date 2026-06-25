/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.ethel.fcgi.core.posix.Posix;
import red.ethel.fcgi.core.protocol.FcgiConnection;

public final class FcgiService extends BaseService implements Service {
    private static final Logger LOGGER = LoggerFactory.getLogger(FcgiService.class);
    private static final int STDIN_FD = 0;

    private final int listenFd;

    private FcgiService(int listenFd) {
        this.listenFd = listenFd;
    }

    public static Service create() {
        if (!Posix.isSocket(STDIN_FD)) {
            return new CgiService();
        }
        return new FcgiService(STDIN_FD);
    }

    /// Test-only seam: package-visible so FcgiServiceTestFactory (test source)
    /// can target an arbitrary listening fd instead of the real fd 0.
    static FcgiService forListenFd(int listenFd) {
        return new FcgiService(listenFd);
    }

    @Override
    public void serve(Handler handler) {
        LOGGER.debug("FcgiService.serve");
        while (true) {
            int clientFd = Posix.accept(listenFd);
            LOGGER.debug("accepted fd {}", clientFd);
            executor.execute(() -> handleConnection(clientFd, handler));
        }
    }

    private void handleConnection(int clientFd, Handler handler) {
        try (var connection = new FcgiConnection(Posix.inputStream(clientFd), Posix.outputStream(clientFd))) {
            var env = connection.readRequestHeader();
            try (var out = connection.stdout()) {
                handler.handle(new FCGIExchange(env, connection.stdin(), out));
            }
            connection.finish(0);
        } catch (Exception e) {
            LOGGER.error("Exception handling FastCGI connection", e);
        } finally {
            try {
                Posix.close(clientFd);
            } catch (FCGIException e) {
                LOGGER.warn("Failed to close client fd {}", clientFd, e);
            }
        }
    }

    @Override
    public void close() {
        // nothing to release: Posix holds no per-process resources to close
    }

    @Override
    protected Executor defaultExecutor() {
        // Platform threads, not virtual: accept()/read()/write() are blocking
        // FFM downcalls that pin their carrier thread for the call's duration.
        // Virtual threads here would reproduce the carrier starvation bug from
        // commit 8a55d05.
        ThreadFactory factory = Thread.ofPlatform().name("worker", 1).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
