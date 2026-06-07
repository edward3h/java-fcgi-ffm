dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("junit", "6.0.3")
            library("junit-api", "org.junit.jupiter", "junit-jupiter-api").versionRef("junit")
            library("junit-params", "org.junit.jupiter", "junit-jupiter-params").versionRef("junit")

            library("jspecify", "org.jspecify:jspecify:1.0.0")
            library("slf4j", "org.slf4j:slf4j-api:2.0.17")

            library("publish-on-central", "org.danilopianini:publish-on-central:9.1.13")

            library("avaje-spi", "io.avaje:avaje-spi-service:2.17")
            version("rainbowgum", "0.8.2")
            library("rainbowgum-core", "io.jstach.rainbowgum", "rainbowgum-core").versionRef("rainbowgum")
            library("rainbowgum-slf4j", "io.jstach.rainbowgum", "rainbowgum-slf4j").versionRef("rainbowgum")
            library("rainbowgum-pattern", "io.jstach.rainbowgum", "rainbowgum-pattern").versionRef("rainbowgum")
            library("rainbowgum-all", "io.jstach.rainbowgum", "rainbowgum").versionRef("rainbowgum")

            version("testcontainers", "1.21.4")
            library("testcontainers", "org.testcontainers", "testcontainers").versionRef("testcontainers")
            library("testcontainers-junit5", "org.testcontainers", "junit-jupiter").versionRef("testcontainers")
        }
    }
}