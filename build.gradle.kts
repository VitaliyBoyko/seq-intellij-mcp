// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

import org.gradle.api.tasks.testing.Test
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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1") {
        exclude(group = "org.jetbrains.kotlin") // Use the platform's Kotlin runtime.
    }
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1") {
        exclude(group = "org.jetbrains.kotlin")
    }

    intellijPlatform {
        intellijIdeaUltimate("263.7307.200")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = effectivePluginVersion

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map { readme ->
            val lines = readme.lines()
            val opening = lines.indexOf("<!-- Plugin description -->")
            val closing = lines.indexOf("<!-- Plugin description end -->")
            require(opening >= 0 && closing > opening) { "README.md must contain ordered plugin description markers." }
            markdownToHTML(lines.subList(opening + 1, closing).joinToString("\n"))
        }

        val releaseHistory = project.changelog
        changeNotes = basePluginVersion.map { pluginVersion ->
            with(releaseHistory) {
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

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

tasks {
    withType<Test>().configureEach {
        // Bundled Code Provenance analytics can keep fixture temp directories open on Windows.
        systemProperty("intellij.code.provenance.enabled", "false")
    }

    test {
        exclude("**/SeqCloudIntegrationTest*", "**/SeqSandboxUiTest*")
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

intellijPlatformTesting {
    testIde.register("sandboxUiTest") {
        testFramework(TestFrameworkType.Platform)
        task {
            description = "Tests the connection button through saved settings and the production client against an authenticated Seq sandbox."
            setExcludes(emptyList())
            include("**/SeqSandboxUiTest.class")
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Uses a real Seq sandbox") { true }
        }
    }

    testIde.register("cloudIntegrationTest") {
        task {
            description = "Tests HTTPS, SQL, and WebSocket search/live capture against an explicitly configured Seq server."
            setExcludes(emptyList())
            include("**/SeqCloudIntegrationTest.class")
            // Credentials are read from the test process environment, never Gradle properties or task inputs.
            outputs.upToDateWhen { false }
            outputs.doNotCacheIf("Uses a live Seq server") { true }
        }
    }
}
