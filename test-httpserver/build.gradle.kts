plugins {
    application
    id("java-convention")
    id("org.graalvm.buildtools.native") version "0.11.1"
}

dependencies {
    runtimeOnly(project(":httpserver"))
    runtimeOnly(project(":test-rgconfig"))
    runtimeOnly(libs.rainbowgum.core)
    runtimeOnly(libs.rainbowgum.slf4j)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("5.12.1")
        }
    }
}

application {
    mainClass = "red.ethel.fcgi.testhttpserver.App"
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "test-httpserver.fcgi"
            sharedLibrary = false
            systemProperties.put("java.library.path", "/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib:/usr/lib/x86_64-linux-gnu")
            runtimeArgs.addAll(
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

val prepareWeb by tasks.registering(Copy::class) {
    into(layout.buildDirectory.dir("public"))
    from(tasks.named("nativeCompile"), layout.projectDirectory.file("src/main/resources/.htaccess"))
}

val deployWeb by tasks.registering(Exec::class) {
    dependsOn(prepareWeb)
    commandLine(
        "rsync",
        "-avz",
        layout.buildDirectory
            .dir("public")
            .get()
            .asFile,
        "nodetest:techtest3.ordoacerbus.com",
    )
}
