plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

base.archivesName = "LogonMC"

dependencies {
    implementation(project(":api"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)

    implementation(libs.hikari)
    implementation(libs.mysql) {
        // Only needed for the X DevAPI, not for JDBC.
        exclude(group = "com.google.protobuf")
    }
    implementation(libs.sqlite)
    implementation(libs.h2)
    implementation(libs.bouncycastle)
    implementation(libs.bcrypt)

    testImplementation(libs.velocity.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks {
    shadowJar {
        archiveClassifier = ""
        // sqlite-jdbc and H2 are not relocated: sqlite's JNI symbols are bound to its package name.
        relocate("com.zaxxer.hikari", "dev.lndf.logonmc.lib.hikari")
        relocate("org.bouncycastle", "dev.lndf.logonmc.lib.bouncycastle")
        relocate("at.favre.lib", "dev.lndf.logonmc.lib.favre")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class")
        // Keep SQLite natives for 64-bit x86/ARM servers only.
        exclude { element ->
            val path = element.path
            path.startsWith("org/sqlite/native/") && !element.isDirectory &&
                !Regex("org/sqlite/native/(Linux|Linux-Musl|Windows|Mac)/(x86_64|aarch64)/.*").matches(path)
        }
        mergeServiceFiles()
        // Only Argon2 is used from Bouncy Castle; drivers load classes reflectively and stay whole.
        minimize {
            exclude(project(":api"))
            exclude(dependency("com.zaxxer:.*"))
            exclude(dependency("com.mysql:.*"))
            exclude(dependency("org.xerial:.*"))
            exclude(dependency("com.h2database:.*"))
            exclude(dependency("at.favre.lib:.*"))
        }
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

val generatedSources = layout.buildDirectory.dir("generated/sources/buildconstants")

// Feeds the version into @Plugin through a generated BuildConstants class.
val generateBuildConstants = tasks.register("generateBuildConstants") {
    val version = project.version.toString()
    inputs.property("version", version)
    outputs.dir(generatedSources)
    doLast {
        val file = generatedSources.get().file("dev/lndf/logonmc/BuildConstants.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package dev.lndf.logonmc;

            final class BuildConstants {
                static final String VERSION = "$version";

                private BuildConstants() {
                }
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildConstants)
}
