plugins {
    application
    id("java-convention")
    id("graalvm-native-convention")
    id("org.ethelred.kiwiproc") version "0.12"
}

dependencies {
    implementation(libs.hikari)
    implementation(libs.mysql.connector)
    implementation("io.avaje:avaje-inject:12.6")
    implementation("io.avaje:avaje-http-api:3.9")
    implementation("io.avaje:avaje-jex:3.6")
    implementation("io.avaje:avaje-jsonb:3.14")
    implementation("io.avaje:avaje-config:5.2")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

    annotationProcessor("io.avaje:avaje-inject-generator:12.6")
    annotationProcessor("io.avaje:avaje-http-jex-generator:3.9")
    annotationProcessor("io.avaje:avaje-jsonb-generator:3.14")

    runtimeOnly(project(":httpserver"))
    runtimeOnly(project(":test-rgconfig"))
    runtimeOnly(libs.rainbowgum.core)
    runtimeOnly(libs.rainbowgum.slf4j)
}

kiwiProc {
    dataSources {
        register("default") {
            driverClassName = "com.mysql.cj.jdbc.Driver"
            liquibaseChangelog = file("$projectDir/src/main/resources/changelog.xml")
        }
    }
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
                implementation(libs.testcontainers.mysql)
                implementation("com.google.truth:truth:1.4.5")
                // kiwiproc declares a Dependabot CVE constraint forcing commons-compress >= 1.26.0,
                // whose TarArchiveOutputStream (used by testcontainers' copyFileToContainer) needs
                // commons-codec.Charsets at runtime; testcontainers itself only pulls compress 1.24.0,
                // which doesn't need it, so the dependency is otherwise absent from the classpath.
                runtimeOnly("commons-codec:commons-codec:1.22.0")
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
                                .file("native/nativeCompile/test-avaje-bin")
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
    mainClass = "red.ethel.fcgi.testavaje.App"
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "test-avaje-bin"
            sharedLibrary = false
            systemProperties.put("java.library.path", "/usr/java/packages/lib:/usr/lib64:/lib64:/lib:/usr/lib:/usr/lib/x86_64-linux-gnu")
            systemProperties.put("com.zaxxer.hikari.useReflectionProxyFactory", "true")
            runtimeArgs.addAll(
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

// Generates a shell wrapper test-avaje.fcgi that exports MySQL credentials from .env
// and exec's the actual binary. FcgidInitialEnv is not usable in .htaccess on all servers,
// so the wrapper is the portable way to inject env vars into the FastCGI process.
val generateWrapper by tasks.registering {
    val envFile = rootProject.file(".env")
    val outputFile = layout.buildDirectory.file("generated/test-avaje.fcgi")
    inputs.file(envFile)
    outputs.file(outputFile)
    doLast {
        val env =
            envFile
                .readLines()
                .filter { "=" in it && !it.startsWith("#") }
                .associate { line ->
                    val key = line.substringBefore("=").trim()
                    val raw = line.substringAfter("=").trim()
                    key to raw.removeSurrounding("\"").removeSurrounding("'")
                }
        val exports =
            listOf("MYSQL_HOST", "MYSQL_DATABASE", "MYSQL_USERNAME", "MYSQL_PASSWORD")
                .mapNotNull { key -> env[key]?.let { value -> "export $key='$value'" } }
                .joinToString("\n")
        val script = "#!/bin/sh\n$exports\nexec \"\$(dirname \"\$0\")/test-avaje-bin\"\n"
        val outFile = outputFile.get().asFile
        outFile.writeText(script)
        outFile.setExecutable(true, false)
    }
}

val prepareWeb by tasks.registering(Copy::class) {
    into(layout.buildDirectory.dir("public"))
    from(tasks.named("nativeCompile"))
    from(generateWrapper.map { it.outputs.files })
    from(layout.projectDirectory.file("src/main/resources/.htaccess"))
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
