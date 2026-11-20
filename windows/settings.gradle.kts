pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // NewPipe Extractor (the Music tab's YouTube songs) is published there
        maven("https://jitpack.io")
    }
}
rootProject.name = "MangaCracker Windows"
