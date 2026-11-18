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
        // Google-hosted Maven Central mirror avoids transient 429 rate limits
        // from repo.maven.apache.org on GitHub-hosted runners.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
    }
}
rootProject.name = "MLKitIsolatedDiagnostic"
include(":app")
