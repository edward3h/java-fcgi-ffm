/* (C) Edward Harman 2026 */
package red.ethel.fcgi.rg;

import io.avaje.spi.ServiceProvider;
import io.jstach.rainbowgum.LogConfig;
import io.jstach.rainbowgum.RainbowGum;
import io.jstach.rainbowgum.output.FileOutput;
import io.jstach.rainbowgum.pattern.format.PatternEncoderBuilder;
import io.jstach.rainbowgum.spi.RainbowGumServiceProvider;
import java.util.Optional;

@ServiceProvider(RainbowGumServiceProvider.class)
public class RGConfig implements RainbowGumServiceProvider.RainbowGumProvider {
    @Override
    public Optional<RainbowGum> provide(LogConfig config) {

        return RainbowGum.builder(config) //
                .route(r -> {
                    r.level(System.Logger.Level.INFO, "red.ethel");
                    r.level(System.Logger.Level.DEBUG, "red.ethel.fcgi.testhttpserver.App");
                    r.level(System.Logger.Level.DEBUG, "red.ethel.fcgi.testjex.App");
                    r.appender("file", a -> {
                        a.encoder(new PatternEncoderBuilder("file")
                                // We use the pattern encoder which follows logback pattern
                                // syntax.
                                .pattern("%date{ISO8601} [%thread] %-5level %logger{15} - %msg%n%ex")
                                // We use properties to override the above pattern if set.
                                .fromProperties(config.properties())
                                .build());
                        a.output(FileOutput.of(builder -> {
                            builder.append(true)
                                    .fileName("./log_test-httpserver.log")
                                    .prudent(true);
                        }));
                    });
                }) //
                .optional();
    }
}
