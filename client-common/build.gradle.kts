plugins {
    `java-library`
}

dependencies {
    api(project(":common"))

    api(libs.jse.spi.opus)
    api(libs.jse.spi.vorbis)
    api(libs.soundlibs.mp3spi)
}
