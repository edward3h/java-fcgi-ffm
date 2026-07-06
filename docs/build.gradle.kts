import org.asciidoctor.gradle.jvm.AsciidoctorTask

plugins {
    id("org.asciidoctor.jvm.convert") version "4.0.5"
}

repositories {
    mavenCentral()
}

tasks.named<AsciidoctorTask>("asciidoctor").configure {
    inputs.files("src/docs/asciidoc")
    baseDirFollowsSourceDir()
    attributes(
        mapOf(
            "build-dir" to layout.buildDirectory.get().toString(),
            "revnumber" to rootProject.version
        )
    )
}

tasks.named("build") {
    dependsOn(tasks.named("asciidoctor"))
}
