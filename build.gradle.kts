// https://github.com/diffplug/spotless/issues/747
buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    base
    id("org.openrewrite.rewrite") version "7.26.0"
}

repositories {
    mavenCentral()
}

dependencies {
    rewrite(platform("org.openrewrite.recipe:rewrite-recipe-bom:3.24.0"))
    rewrite("org.openrewrite.recipe:rewrite-migrate-java")
    rewrite("org.openrewrite.recipe:rewrite-logging-frameworks")
    rewrite("org.openrewrite.recipe:rewrite-static-analysis")
}

rewrite {
    activeRecipe("red.ethel.fcgi.General")
    isExportDatatables = true
}

tasks.named("check") {
    dependsOn(tasks.named("rewriteDryRun"))
}
