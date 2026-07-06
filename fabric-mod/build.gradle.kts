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
    implementation(libs.fabric.permissions.api)
    compileOnly(libs.luckperms.api)
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
    include(project(":shared-core"))
    include(project(":server-core"))
    include(project(":client-core"))
    include(libs.fabric.permissions.api)
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
