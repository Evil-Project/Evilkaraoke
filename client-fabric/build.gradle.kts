plugins {
    alias(libs.plugins.fabric.loom)
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("Evilkaraoke-Fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.jse.spi.opus)
    implementation(libs.jse.spi.vorbis)
    implementation(libs.jse.api)
    implementation(libs.soundlibs.mp3spi)
    implementation(libs.soundlibs.tritonus)
    implementation(libs.soundlibs.jlayer)
    implementation(libs.vorbis.java.core)

    implementation(project(":common"))
    implementation(project(":client-common"))
    include(project(":common"))
    include(project(":client-common"))
    include(libs.jse.spi.opus)
    include(libs.jse.spi.vorbis)
    include(libs.jse.api)
    include(libs.soundlibs.mp3spi)
    include(libs.soundlibs.tritonus)
    include(libs.soundlibs.jlayer)
    include(libs.vorbis.java.core)
}

loom {
    runs.configureEach {
        jvmArguments.add("--sun-misc-unsafe-memory-access=allow")
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}
