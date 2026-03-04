dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("junit", "6.0.3")
            library("junit-api", "org.junit.jupiter", "junit-jupiter-api").versionRef("junit")
            library("junit-params", "org.junit.jupiter", "junit-jupiter-params").versionRef("junit")

            library("jspecify", "org.jspecify:jspecify:1.0.0")
            library("slf4j", "org.slf4j:slf4j-api:2.0.17")

            library("publish-on-central", "org.danilopianini:publish-on-central:9.1.13")

            library("avaje-spi", "io.avaje:avaje-spi-service:2.12")
        }
    }
}