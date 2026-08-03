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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication { create<BasicAuthentication>("basic") }
            credentials {
                // Mapbox requires the literal string "mapbox" as the username.
                // The password is a "secret token" (sk.*) generated in your Mapbox dashboard
                // with the "Downloads: Read" scope — kept in local.properties or the env.
                username = "mapbox"
                val localProps = java.util.Properties().apply {
                    val f = rootDir.resolve("local.properties")
                    if (f.exists()) f.inputStream().use { load(it) }
                }
                password = localProps.getProperty("MAPBOX_DOWNLOADS_TOKEN")
                    ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
                            ?: ""
            }
        }
    }
}

rootProject.name = "KoveDash"
include(":app")
