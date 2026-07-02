plugins {
    `java-library`
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    api(project(":common"))
    compileOnly(libs.paper.api)
    compileOnly(libs.luckperms.api)
    implementation(libs.gson)
    testImplementation(libs.paper.api)
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.google.gson", "org.evilproject.evilkaraoke.libs.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("26.2")
    // Accept the Minecraft EULA for the local test server (https://aka.ms/MinecraftEULA).
    systemProperty("com.mojang.eula.agree", "true")
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
}
