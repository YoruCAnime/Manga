pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipe Extractor (the Music tab's YouTube songs) is published there
        maven("https://jitpack.io")
    }
}
rootProject.name = "MangaCracker"
include(":app")
