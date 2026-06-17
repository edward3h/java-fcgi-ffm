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
  kiwiproc's annotation processor generates `$CounterDAO$Provider`, annotated `@Singleton` (`jakarta.inject`) with a `@Named("default") DataSource` constructor parameter. avaje-inject understands plain JSR-330 `@Singleton`/`@Named` natively, so this provider is picked up automatically — no manual wiring class needed.

- **`DataSourceFactory`** (`@Factory`, avaje-inject) — the only piece that supplies the `DataSource` kiwiproc's generated provider needs:
  ```java
  @Factory
  public class DataSourceFactory {
      @Bean(destroyMethod = "close")
      @Named("default")
      DataSource dataSource() { /* HikariDataSource built from MYSQL_HOST/DATABASE/USERNAME/PASSWORD env vars */ }
  }
  ```
  This replaces the manual wiring currently done by hand in `test-mysql`'s `App.java`.

- **`CounterController`** (`@Controller @Path("/db/counters")`, avaje-http) — constructor-injected with `CounterDAO`:
  - `@Get` → `List<CounterDAO.Counter>` as JSON (avaje-jsonb).
  - `@Post("/{name}/increment")` → calls `incrementByName`; if it returns `false`, throws `io.avaje.jex.http.NotFoundException` (avaje-jex maps this to a 404 automatically); otherwise returns the updated `Counter` (re-fetched via `findByName`) as JSON.

- **`App`** — `static void main() { AvajeJex.start(); }`. This is avaje's own idiomatic bootstrap (confirmed against `avaje/avaje-jex`'s `examples/example-http-generation`): it builds a `BeanScope`, registers every generated `Routing.HttpService` route with a `Jex` instance, and starts it on port 8080 (avaje-jex's default — same port `test-jex` sets explicitly).

## API (idiomatic JSON, intentionally different from test-mysql's plain-text wire format)

```
GET  /db/counters                    -> 200 [{"name":"page_views","value":0}, ...]
POST /db/counters/{name}/increment   -> 200 {"name":"page_views","value":1}
                                      -> 404 if name unknown
```

## Schema / build-time SQL validation

Two schema definitions of the same `counter` table, for two different purposes:

- **`src/main/resources/changelog.xml`** (Liquibase) — consumed by the kiwiproc Gradle plugin, which spins up a MySQL instance via testcontainers *during the Gradle build itself* (not just `integrationTest`), applies this changelog, and validates `CounterDAO`'s SQL against the real schema at annotation-processing time. **This means plain `./gradlew build`/`compileJava` on this module now requires Docker** — previously only `integrationTest` did, project-wide. Worth calling out since it's a new build-time constraint.
- **`src/main/resources/schema.sql`** — unchanged in shape from `test-mysql`'s, used by `AppIntegrationTest`'s own `MySQLContainer.withInitScript(...)` for the full HTTP-level runtime test (which exercises the actual native-compiled `.fcgi` binary through Apache/mod_fcgid, same pattern as `test-mysql`/`test-jex`). This is a separate container from kiwiproc's build-time one and serves a different purpose, so the duplication is intentional, not accidental.

```sql
CREATE TABLE IF NOT EXISTS counter (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL UNIQUE,
    value INT NOT NULL DEFAULT 0
);
INSERT IGNORE INTO counter (name, value) VALUES ('page_views', 0), ('api_calls', 0);
```

## GraalVM native image

Mirrors `test-mysql`'s `graalvmNative` block exactly, including `com.zaxxer.hikari.useReflectionProxyFactory=true` — avaje-http, avaje-inject, avaje-jsonb, and kiwiproc's generated DAO providers are all reflection-free (APT-generated), so the only native-image accommodation needed is the existing Hikari one.

## Testing

Mirrors `test-mysql`'s `AppIntegrationTest`: same `MySQLContainer` + `GenericContainer` (built from `docker/mysql/Dockerfile`) + `schema.sql` pattern, same `nativeCompile`-gated `integrationTest` Gradle task wiring, just asserting JSON response bodies instead of plain text.

## Out of scope

- No changes to `test-mysql`, `test-jex`, or their dependency versions.
- No OpenAPI generation (avaje-http supports it, but it's not needed to validate this stack).
- No attempt to make kiwiproc's compile-time MySQL validation reusable/shared across modules — it's self-contained to `test-avaje`.
