plugins {
    java
    id("maven-publish")
    id("signing")
    id("com.vanniktech.maven.publish")
}

group = rootProject.group
version = rootProject.version

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name = "java-fcgi-ffm"
        description = "FastCGI implementation using Java Foreign Function & Memory API"
        url = "https://github.com/edward3h/java-fcgi-ffm"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        scm {
            connection = "https://github.com/edward3h/java-fcgi-ffm.git"
            url = "https://github.com/edward3h/java-fcgi-ffm"
        }
        developers {
            developer {
                name = "Edward Harman"
                email = "jaq@ethelred.org"
            }
        }
    }
}

signing {
    val signingKey = findProperty("signingKey").toString()
    val signingPassword = findProperty("signingPassword").toString()
    useInMemoryPgpKeys(signingKey, signingPassword)
}
