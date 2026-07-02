plugins {
    alias(libs.plugins.fabric.loom)
}

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)

    // Only the networking module is needed for custom payloads; pulling the whole
    // fabric-api bundle clashes with Mojang mappings (fabric-content-registries-v0
    // javadoc namespace), so we request just this module at the matching version.
    modImplementation(fabricApi.module("fabric-networking-api-v1", libs.versions.fabric.api.get()))

    implementation(project(":common"))
    implementation(project(":client-common"))
    include(project(":common"))
    include(project(":client-common"))
}
