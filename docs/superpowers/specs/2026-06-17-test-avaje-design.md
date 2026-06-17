# test-avaje: avaje-http + avaje-inject + kiwiproc example

## Context

`java-fcgi-ffm` lets any framework built on `com.sun.net.httpserver` run as a FastCGI process. The project already has example modules proving this for a couple of stacks: `test-httpserver` (plain JDK `HttpServer`), `test-jex` (avaje-jex), and `test-mysql` (plain `HttpHandler` + HikariCP, talking to MySQL).

The next stack to validate is the "full avaje" approach: annotation-routed controllers (avaje-http) wired together by compile-time dependency injection (avaje-inject), running on avaje-jex (already proven to work through this project's `httpserver` SPI shim in `test-jex`). On top of that, the database access layer will use `kiwiproc` — the author's own compile-time-checked JDBC framework — instead of hand-written JDBC, to validate that combination too.

The new module reimplements `test-mysql`'s counters API (`GET`/`POST` against a `counter` table) so the two approaches — raw `HttpHandler` vs. the avaje/kiwiproc stack — can be compared side by side. `test-mysql` is left untouched.

## Module

New Gradle module `test-avaje`, package `red.ethel.fcgi.testavaje`, added to root `settings.gradle.kts` alongside the existing example modules. Reuses `docker/mysql/Dockerfile`, the testcontainers integration-test pattern, and the GraalVM native-image setup already established in `test-mysql`/`test-jex`.

## Dependency stack

All from Maven Central, current stable versions (verified just now against `maven-metadata.xml`, not assumed from training data):

| Library | Artifacts | Version |
|---|---|---|
| avaje-inject | `avaje-inject`, `avaje-inject-generator` | 12.6 |
| avaje-http | `avaje-http-api`, `avaje-http-jex-generator` | 3.9 |
| avaje-jex | `avaje-jex` | 3.6 |
| avaje-jsonb | `avaje-jsonb`, `avaje-jsonb-generator` | 3.14 |
| avaje-config | `avaje-config` | 5.2 |
| kiwiproc | Gradle plugin `org.ethelred.kiwiproc` (auto-adds `processor`/`runtime`) | 0.11 |

Plus `jakarta.inject:jakarta.inject-api:2.0.1` as a plain `implementation` dependency — kiwiproc's generated code references `jakarta.inject.Singleton`/`@Named` directly, and the published kiwiproc quickstart calls this out explicitly.

`test-jex` currently pins `avaje-jex:3.5-RC7`; that file is untouched. `test-avaje` uses the current stable `3.6` independently.

## Build setup

`test-avaje/build.gradle.kts`, modelled on `test-mysql`'s structure:

```kotlin
plugins {
    application
    id("java-convention")
    id("org.graalvm.buildtools.native") version "0.11.1"
    id("org.ethelred.kiwiproc") version "0.11"
}

dependencies {
    implementation("io.avaje:avaje-inject:12.6")
    implementation("io.avaje:avaje-http-api:3.9")
    implementation("io.avaje:avaje-jex:3.6")
    implementation("io.avaje:avaje-jsonb:3.14")
    implementation("io.avaje:avaje-config:5.2")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    annotationProcessor("io.avaje:avaje-inject-generator:12.6")
    annotationProcessor("io.avaje:avaje-http-jex-generator:3.9")
    annotationProcessor("io.avaje:avaje-jsonb-generator:3.14")
    // avaje-inject-generator and avaje-jsonb-generator round-trip kiwiproc's
    // generated $CounterDAO$Provider in the same compilation (javac multi-round
    // annotation processing) -- see Components section below.

    runtimeOnly(project(":httpserver"))
    runtimeOnly(project(":test-rgconfig"))
    runtimeOnly(libs.rainbowgum.core)
    runtimeOnly(libs.rainbowgum.slf4j)
}

kiwiProc {
    dataSources {
        register("default") {
            driverClassName = "com.mysql.cj.jdbc.Driver"
            liquibaseChangelog = file("$projectDir/src/main/resources/changelog.xml")
        }
    }
}
```

The `org.ethelred.kiwiproc` plugin (applied above) auto-adds `org.ethelred.kiwiproc:processor` to `annotationProcessor` and `org.ethelred.kiwiproc:runtime` to `implementation` itself — they're not declared by hand. The `application`/`graalvmNative`/`prepareWeb`/`deployWeb` blocks below the `dependencies {}` block mirror `test-mysql`'s verbatim (imageName `test-avaje-bin`, etc.) and are omitted here for brevity — see the GraalVM native image section.

## Components

- **`CounterDAO`** (`@DAO` interface, kiwiproc) — replaces a hand-written repository entirely:
  ```java
  @DAO
  public interface CounterDAO {
      @Json record Counter(String name, int value) {}

      @SqlQuery("SELECT name, value FROM counter ORDER BY name")
      List<Counter> findAll();

      @SqlUpdate("UPDATE counter SET value = value + 1 WHERE name = :name")
      boolean incrementByName(String name);

      @SqlQuery("SELECT name, value FROM counter WHERE name = :name")
      @Nullable Counter findByName(String name);
  }
  ```
  kiwiproc's annotation processor generates `$CounterDAO$Provider`, annotated `@Singleton` (`jakarta.inject`) with a `@Named("default") DataSource` constructor parameter — confirmed by reading `kiwiproc`'s `ProviderGenerator.java` and the generated-code example in its own `test-micronaut` module (default `DependencyInjectionStyle.JAKARTA`, no kiwiproc-side config needed for that).

  avaje-inject's own docs state it recognises `@Singleton`/`@Named` from `jakarta.inject` directly, not just its own `io.avaje.inject.*` equivalents. The cross-processor question — does avaje-inject-generator see a class that *kiwiproc's* processor generated, in the same compile? — relies on standard javac multi-round annotation processing (a processor's generated sources are fed back in as input to a subsequent round, visible to every registered processor, including ones from other libraries). This is the same mechanism avaje's own example (`example-http-generation`) relies on for avaje-http-jex-generator's generated `$Controller$Route` classes to be picked up by avaje-inject-generator — proven to work there, and not something specific to avaje-http. Risk: if it doesn't hold for kiwiproc specifically, the fallback is a one-line `@Factory` method (`@Bean CounterDAO counterDAO(DataSource ds) { return new $CounterDAO$Provider(ds); }`) — noted here so the implementer isn't stuck if the automatic pickup doesn't pan out.

- **`DataSourceFactory`** (`@Factory`, avaje-inject) — the only piece that supplies the `DataSource` kiwiproc's generated provider needs:
  ```java
  @Factory
  public class DataSourceFactory {
      @Bean(destroyMethod = "close")
      @Named("default")
      DataSource dataSource() { /* HikariDataSource built from MYSQL_HOST/DATABASE/USERNAME/PASSWORD env vars */ }
  }
  ```
  This replaces the manual wiring currently done by hand in `test-mysql`'s `App.java`. The env vars it reads (`MYSQL_HOST`/`MYSQL_DATABASE`/`MYSQL_USERNAME`/`MYSQL_PASSWORD`) are the same ones `test-mysql/build.gradle.kts`'s `generateWrapper` task injects via the shell-wrapper pattern for DreamHost deployment — `test-avaje`'s `build.gradle.kts` reuses that same task verbatim (just renamed to `test-avaje.fcgi`/`test-avaje-bin`).

- **`CounterController`** (avaje-http) — constructor-injected with `CounterDAO`:
  ```java
  import io.avaje.http.api.Controller;
  import io.avaje.http.api.Get;
  import io.avaje.http.api.Path;
  import io.avaje.http.api.Post;
  import io.avaje.jex.http.NotFoundException;

  @Controller
  @Path("/db/counters")
  class CounterController {
      private final CounterDAO dao;
      CounterController(CounterDAO dao) { this.dao = dao; }

      @Get
      List<CounterDAO.Counter> getAll() { return dao.findAll(); }

      @Post("/{name}/increment")
      CounterDAO.Counter increment(String name) {
          if (!dao.incrementByName(name)) {
              throw new NotFoundException("Not found: " + name);
          }
          return dao.findByName(name);
      }
  }
  ```
  `@Get`/`@Post` return values are serialized to JSON automatically (avaje-jsonb, since `CounterDAO.Counter` is `@Json`-annotated). `NotFoundException` is mapped to a 404 by avaje-jex's built-in exception handling.

- **`App`** — `static void main() { AvajeJex.start(); }`. `io.avaje.jex.AvajeJex` is a real class shipped in the `avaje-jex` artifact itself (`avaje-jex/src/main/java/io/avaje/jex/AvajeJex.java` in the `avaje/avaje-jex` GitHub repo, fetched and read directly during design — not inferred). Its `start()` builds a `BeanScope.builder().build()`, resolves an optional `Jex` bean (or `Jex.create()` if none registered), calls `jex.configureWith(beanScope)` to register every generated `Routing.HttpService` route, then starts on port 8080 (avaje-jex's documented default, same port `test-jex` sets explicitly via `.port(8080)`).
  This repo's existing `test-jex` module pins the much older `avaje-jex:3.5-RC7` and predates this helper, so it understandably doesn't use it — it wires `Jex.create()...start()` by hand instead. `test-avaje` pins current stable `3.6`, where `AvajeJex` is present; confirmed by fetching `avaje/avaje-jex`'s own `examples/example-http-generation` (`src/main/java/main/Main.java`), which uses exactly `AvajeJex.start();` as its entire `main()`.

## API (idiomatic JSON, intentionally different from test-mysql's plain-text wire format)

```
GET  /db/counters                    -> 200 [{"name":"page_views","value":0}, ...]
POST /db/counters/{name}/increment   -> 200 {"name":"page_views","value":1}
                                      -> 404 if name unknown
```

## Schema / build-time SQL validation

Two schema definitions of the same `counter` table, for two different purposes:

- **`src/main/resources/changelog.xml`** (Liquibase) — consumed by the kiwiproc Gradle plugin, which spins up a MySQL instance via testcontainers *during the Gradle build itself* (not just `integrationTest`), applies this changelog, and validates `CounterDAO`'s SQL against the real schema at annotation-processing time. **This means plain `./gradlew build`/`compileJava` on this module now requires Docker** — previously only `integrationTest` did, project-wide. Worth calling out since it's a new build-time constraint.
- **`src/main/resources/schema.sql`** — unchanged in shape from `test-mysql`'s, used by `AppIntegrationTest`'s own `MySQLContainer.withInitScript(...)` for the full HTTP-level runtime test (which exercises the actual native-compiled `.fcgi` binary through Apache/mod_fcgid, same pattern as `test-mysql`/`test-jex`). This is a separate container from kiwiproc's build-time one and serves a different purpose, so the duplication is intentional, not accidental — but it is a real drift risk (a column added to one and not the other won't be caught by anything). Mitigation: each file gets a one-line comment pointing at the other ("keep in sync with changelog.xml" / "keep in sync with schema.sql"). Acceptable for a small example module; not a pattern to propagate to production code without a real single-source-of-truth mechanism.

```sql
CREATE TABLE IF NOT EXISTS counter (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL UNIQUE,
    value INT NOT NULL DEFAULT 0
);
INSERT IGNORE INTO counter (name, value) VALUES ('page_views', 0), ('api_calls', 0);
```

## GraalVM native image

Mirrors `test-mysql`'s `graalvmNative` block verbatim — same `imageName`, `java.library.path`, `--enable-native-access=ALL-UNNAMED`, and `com.zaxxer.hikari.useReflectionProxyFactory=true`. `test-mysql` already builds and runs a native image successfully with this exact dependency pair (HikariCP + `mysql-connector-j`) and no other reflection config, so MySQL Connector/J's native-image needs are already covered by copying that block — they are not a new gap introduced by this module. The genuinely new libraries here (avaje-http, avaje-inject, avaje-jsonb, kiwiproc's generated DAO providers) are all reflection-free/APT-generated, so they add no additional native-image accommodation beyond what `test-mysql` already requires. Liquibase (used by the kiwiproc Gradle plugin to apply `changelog.xml` against its build-time MySQL container) runs only in the Gradle build JVM, never in the shipped binary, so it has no native-image implications at all.

## Testing

Mirrors `test-mysql`'s `AppIntegrationTest`: same `MySQLContainer` + `GenericContainer` (built from `docker/mysql/Dockerfile`) + `schema.sql` pattern, same `nativeCompile`-gated `integrationTest` Gradle task wiring, just asserting JSON response bodies instead of plain text.

## Out of scope

- No changes to `test-mysql`, `test-jex`, or their dependency versions.
- No OpenAPI generation (avaje-http supports it, but it's not needed to validate this stack).
- No attempt to make kiwiproc's compile-time MySQL validation reusable/shared across modules — it's self-contained to `test-avaje`.
