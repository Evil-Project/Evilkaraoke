plugins {
    alias(libs.plugins.neoforge.moddev)
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("Evilkaraoke-NeoForge")
}

neoForge {
    version = "26.2.0.7-beta"

    runs {
        create("client") {
            client()
            jvmArgument("--sun-misc-unsafe-memory-access=allow")
        }
        create("server") {
            server()
            jvmArgument("--sun-misc-unsafe-memory-access=allow")
        }
    }
}

dependencies {
    implementation(libs.jse.spi.opus)
    implementation(libs.jse.spi.vorbis)
    implementation(libs.jse.api)
    implementation(libs.soundlibs.mp3spi)
    implementation(libs.soundlibs.tritonus)
    implementation(libs.soundlibs.jlayer)
    implementation(libs.vorbis.java.core)

    implementation(project(":shared-core"))
    implementation(project(":server-core"))
    implementation(project(":client-core"))
    jarJar(project(":shared-core"))
    jarJar(project(":server-core"))
    jarJar(project(":client-core"))
    jarJar(libs.jse.spi.opus)
    jarJar(libs.jse.spi.vorbis)
    jarJar(libs.jse.api)
    jarJar(libs.soundlibs.mp3spi)
    jarJar(libs.soundlibs.tritonus)
    jarJar(libs.soundlibs.jlayer)
    jarJar(libs.vorbis.java.core)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(props)
    }
}
