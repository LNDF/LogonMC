import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.SourcesJar

plugins {
    `java-library`
    alias(libs.plugins.maven.publish)
}

base.archivesName = "logonmc-api"

dependencies {
    compileOnlyApi(libs.velocity.api)
}

// Every public API member must be documented: Javadoc warnings fail the build.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

val repoUrl = providers.gradleProperty("repoUrl").get()

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = SourcesJar.Sources()))
    coordinates(project.group.toString(), "logonmc-api", project.version.toString())

    // Uploads, then releases once Central has validated the deployment.
    publishToMavenCentral(automaticRelease = true)
    // Signing is skipped for local publishing (publishToMavenLocal) when no key is configured.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name = "LogonMC API"
        description = "API for LogonMC, a Velocity login plugin with premium autologin, sessions and limbo routing."
        inceptionYear = "2026"
        url = repoUrl
        licenses {
            license {
                name = "GNU Lesser General Public License v3.0"
                url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "lndf"
                name = "LNDF"
                url = "https://github.com/LNDF"
            }
        }
        scm {
            url = repoUrl
            connection = "scm:git:$repoUrl.git"
            developerConnection = "scm:git:$repoUrl.git"
        }
    }
}
