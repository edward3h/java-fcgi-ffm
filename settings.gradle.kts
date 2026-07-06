plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "java-fcgi-ffm"
include(
    "fcgi",
    "httpserver",
    "docs",
    "test-httpserver",
    "test-rgconfig",
    "test-jex",
    "test-mysql",
    "test-avaje"
)
apply(from = "catalog.settings.gradle.kts")
