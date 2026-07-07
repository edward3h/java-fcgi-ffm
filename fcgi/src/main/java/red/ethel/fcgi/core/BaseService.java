/* (C) Edward Harman 2026 */
package red.ethel.fcgi.core;

import java.util.concurrent.Executor;

abstract sealed class BaseService implements Service permits CgiService, FcgiService {
    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public void setExecutor(Executor executor) {
        // No-op: the executor is fixed per implementation to match the threading
        // model it relies on (see defaultExecutor() implementations). Overriding
        // it risks reintroducing carrier-thread starvation or breaking the
        // single-request-per-process model.
    }

    protected final Executor executor = defaultExecutor();

    protected abstract Executor defaultExecutor();
}
