// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-FileCopyrightText: 2000-2021 JetBrains s.r.o.
// SPDX-License-Identifier: MIT AND Apache-2.0
// License: see LICENSE and LICENSES/Apache-2.0.txt in the repository root.

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "seq-mcp-plugin"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
        }
    }
}
