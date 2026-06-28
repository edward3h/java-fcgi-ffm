plugins {
    `java-library`
    id("java-convention")
    id("publishing-convention")
}

dependencies {
    implementation(project(":fcgi"))
    annotationProcessor(libs.avaje.spi)
    implementation(libs.avaje.spi)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.12.1")
        }
    }
}
