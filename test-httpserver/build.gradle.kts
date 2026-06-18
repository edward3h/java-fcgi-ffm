plugins {
    application
    id("java-convention")
    id("org.graalvm.buildtools.native") version "0.11.5"
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

        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(libs.testcontainers)
                implementation(libs.testcontainers.junit5)
                implementation("com.google.truth:truth:1.4.5")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
            }
            targets {
                all {
                    testTask.configure {
                        dependsOn("nativeCompile")
                        jvmArgs("--enable-native-access=ALL-UNNAMED")
                        environment("DOCKER_HOST", "unix:///var/run/docker.sock")
                        environment("DOCKER_API_VERSION", "1.41")
                        systemProperty(
                            "fcgi.binary.path",
                            layout.buildDirectory
                                .file("native/nativeCompile/test-httpserver.fcgi")
                                .get()
                                .asFile.absolutePath,
                        )
                        systemProperty("docker.dir", rootProject.file("docker").absolutePath)
                    }
                }
            }
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
