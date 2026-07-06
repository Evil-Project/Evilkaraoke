plugins {
    `java-library`
}

dependencies {
    api(project(":shared-core"))

    api(libs.jse.spi.opus)
    api(libs.jse.spi.vorbis)
    api(libs.soundlibs.mp3spi)
}
