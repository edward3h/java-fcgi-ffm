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
        this.executor = executor;
    }

    protected Executor executor = defaultExecutor();

    protected abstract Executor defaultExecutor();
}
