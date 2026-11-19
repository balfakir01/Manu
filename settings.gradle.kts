// Google's public mirror of Maven Central is listed first: Maven Central itself often answers
// shared CI runners with "429 Too Many Requests". Both serve the same, signed artifacts.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(centralMirror)
        mavenCentral()
    }
}

rootProject.name = "DataGuard"
include(":app")
