pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Локальный Maven-репозиторий со собранным ffmpeg-kit-next.
        // Появляется после `./nix-android.sh -p android-r27d`
        // (prebuilt/bundle-android-aar-24-maven). Пока папки нет —
        // Gradle просто считает репозиторий пустым.
        maven { url = uri(rootDir.resolve("prebuilt/bundle-android-aar-24-maven")) }
    }
}

rootProject.name = "e-brake"
include(":app")
