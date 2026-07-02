plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    api(project(":common"))
    compileOnly(libs.paper.api)
    implementation(libs.gson)
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.google.gson", "org.evilproject.evilkaraoke.libs.gson")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
