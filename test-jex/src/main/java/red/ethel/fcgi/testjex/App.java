/* (C) Edward Harman 2026 */
package red.ethel.fcgi.testjex;

import io.avaje.jex.Jex;
import io.avaje.jex.http.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    static void main() {
        Jex.create()
                .get("/", ctx -> ctx.text("hello"))
                .get("/one/{id}", ctx -> ctx.text("one-" + ctx.pathParam("id")))
                // workaround for NPE in route matching
//                .post("/*", ctx -> {
//                    throw new NotFoundException(ctx.path());
//                })
                .filter((ctx, chain) -> {
                    LOGGER.debug("before request");
                    chain.proceed();
                    LOGGER.debug("after request");
                })
//                .error(Exception.class, (ctx, exception) -> {
//                    LOGGER.error("Unhandled exception", exception);
//                    ctx.status(500).text("oopsie");
//                })
                .port(8080)
                .start();
    }
}
