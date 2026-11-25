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
        // Google's official GCS mirror of Maven Central. Required
        // because the live Central CDN currently answers 404 for
        // com.arthenica:ffmpeg-kit-* (proven by a CI resolution
        // failure on 2026-11-25) while the mirror serves the
        // artifacts correctly. See TECHNOLOGY_DECISIONS.md TD-001.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
    }
}
rootProject.name = "VideoAIStudio"
include(":app")
