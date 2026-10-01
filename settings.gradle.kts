// SPDX-License-Identifier: AGPL-3.0-or-later
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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "dreamweave-quest"

include(":core")      // pure Kotlin domain: events, fold, game rules, proposals
include(":protocol")  // wire DTOs shared by app and server + JSON Schema emission
include(":server")    // Ktor sync API + remote MCP endpoint

// See build.gradle.kts: `-Pquest.jvmOnly=true` skips the Android modules.
if (providers.gradleProperty("quest.jvmOnly").orNull != "true") {
    include(":data")  // Room, repositories, settings, sync client
    include(":app")   // Compose UI, notifications, widget, vault
}
