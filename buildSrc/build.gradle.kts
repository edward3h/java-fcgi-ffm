plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.7.0")
    implementation(libs.publish.on.central)
    implementation("org.graalvm.buildtools:native-gradle-plugin:0.11.5")
}