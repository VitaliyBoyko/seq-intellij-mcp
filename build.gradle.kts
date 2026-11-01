// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-FileCopyrightText: 2000-2021 JetBrains s.r.o.
// SPDX-License-Identifier: MIT AND Apache-2.0
// License: see LICENSE and LICENSES/Apache-2.0.txt in the repository root.

import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

val basePluginVersion = providers.gradleProperty("version")
val isGithubPrerelease = providers.environmentVariable("GITHUB_RELEASE_PRERELEASE")
    .map(String::toBoolean)
    .orElse(false)
val effectivePluginVersion = providers.provider {
    val pluginVersion = basePluginVersion.get()

    if (!isGithubPrerelease.get()) {
        pluginVersion
    } else {
        val releaseId = providers.environmentVariable("GITHUB_RELEASE_ID").orNull
            ?: throw GradleException("GITHUB_RELEASE_ID is required when publishing a GitHub prerelease.")

        "$pluginVersion-alpha.$releaseId"
    }
}

version = effectivePluginVersion.get()

kotlin {
    jvmToolchain(25)

    compilerOptions {
        // Inherit platform interface defaults without generating bridges to deprecated or experimental APIs.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") {
        exclude(group = "org.jetbrains.kotlin") // Use the platform's Kotlin runtime.
    }
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0") {
        exclude(group = "org.jetbrains.kotlin")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdeaUltimate("263.7307.44")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        version = effectivePluginVersion

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = basePluginVersion.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    publishing {
        token = providers.environmentVariable("JET_BRAINS_TOKEN")
        channels = effectivePluginVersion.map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

tasks {
    test {
        exclude("**/SeqCloudIntegrationTest*")
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

intellijPlatformTesting {
    testIde.register("cloudIntegrationTest") {
        task {
            description = "Tests HTTPS, SQL, and WebSocket search/live capture against an explicitly configured Seq server."
            include("**/SeqCloudIntegrationTest.class")
            // Credentials are read from the test process environment, never Gradle properties or task inputs.
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Uses a live Seq server") { true }
        }
    }
}
