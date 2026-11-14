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
        // Termux's terminal emulator and renderer (Apache 2.0), which Claude Rowed draws its terminal with.
        maven("https://jitpack.io") { content { includeGroup("com.github.termux.termux-app") } }
    }
}

rootProject.name = "SuperTuxRow"
include(":app")
