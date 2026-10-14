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
        // NewPipeExtractor wird ausschließlich über JitPack veröffentlicht.
        maven("https://jitpack.io")
    }
}

rootProject.name = "YouTubeAACompanion"

include(":app")
include(":car")
include(":aauto")