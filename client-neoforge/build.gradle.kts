plugins {
    alias(libs.plugins.neoforge.moddev)
}

neoForge {
    version = "21.11.42"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":client-common"))
    jarJar(project(":common"))
    jarJar(project(":client-common"))
}
