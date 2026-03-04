/* (C) Edward Harman 2026 */
package red.ethel.fcgi.httpserver;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;

final class ContextList {
    private final Map<String, ContextImpl> contextMap = new LinkedHashMap<>();
    private final Lock read;
    private final Lock write;

    ContextList() {
        var rwLock = new ReentrantReadWriteLock();
        read = rwLock.readLock();
        write = rwLock.writeLock();
    }

    HttpContext create(FCGIHttpsServer fcgiHttpsServer, String path, @Nullable HttpHandler handler) {
        write.lock();
        try {
            validatePath(path);
            if (contextMap.containsKey(path)) {
                throw new IllegalArgumentException("Path already exists \"%s\"".formatted(path));
            }
            var context = new ContextImpl(fcgiHttpsServer, path, handler);
            contextMap.put(path, context);
            return context;
        } finally {
            write.unlock();
        }
    }

    private void validatePath(String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path \"%s\"".formatted(path));
        }
    }

    void remove(String path) {
        write.lock();
        try {
            var value = contextMap.remove(path);
            if (value == null) {
                throw new IllegalArgumentException("No context for path \"%s\"".formatted(path));
            }
        } finally {
            write.unlock();
        }
    }

    @Nullable ContextImpl findContext(String path) {
        read.lock();
        try {
            String longest = "";
            ContextImpl longestContext = null;
            for (var entry : contextMap.entrySet()) {
                var testPath = entry.getKey();
                if (path.startsWith(testPath) && testPath.length() > longest.length()) {
                    longest = testPath;
                    longestContext = entry.getValue();
                }
            }
            return longestContext;
        } finally {
            read.unlock();
        }
    }
}
