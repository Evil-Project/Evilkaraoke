pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "Evilkaraoke"
include("common", "server-paper", "client-common", "client-fabric", "client-neoforge")
