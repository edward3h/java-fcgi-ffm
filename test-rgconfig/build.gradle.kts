plugins {
    `java-library`
    id("java-convention")
}

dependencies {
    annotationProcessor(libs.avaje.spi)
    implementation(libs.avaje.spi)
    implementation(libs.rainbowgum.core)
    implementation(libs.rainbowgum.pattern)
}
