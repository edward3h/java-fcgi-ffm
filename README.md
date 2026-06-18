# java-fcgi-ffm

A Java library for running web applications as FastCGI processes, using the Foreign Function & Memory (FFM) API to wrap the native `libfcgi` library.

## What it does

Allows a Java HTTP server to be invoked by a web server (Apache, Nginx, etc.) via FastCGI or CGI. The library auto-detects the environment and adapts accordingly. It implements the `com.sun.net.httpserver` SPI so any framework built on Java's standard `HttpServer` API works without modification.

## Modules

| Module | Description |
|---|---|
| `fcgi` | Core library — FFM bindings for libfcgi, `Service` implementations for FastCGI and CGI |
| `httpserver` | `HttpsServer` adapter implementing the `com.sun.net.httpserver` SPI |
| `test-httpserver` | Example app using Java's built-in `HttpServer` |
| `test-jex` | Example app using the [Jex](https://javalin.io) web framework |
| `test-avaje` | Example app using [avaje-http](https://avaje.io/http/) + [avaje-inject](https://avaje.io/inject/) on [avaje-jex](https://avaje.io/jex/), with [kiwiproc](https://edward3h.github.io/kiwiproc/) for compile-time-checked JDBC |
| `test-rgconfig` | Shared [RainbowGum](https://github.com/jstachio/rainbowgum) logging configuration for example apps |

## Requirements

- Java 25+
- `libfcgi` installed (e.g. `apt install libfcgi-dev` on Debian/Ubuntu)

## Building

```sh
./gradlew build
```

## Usage

Register the `httpserver` module on the classpath and start a standard `HttpServer` — the SPI provider will route requests through FastCGI automatically. See `test-httpserver` for a minimal example.
