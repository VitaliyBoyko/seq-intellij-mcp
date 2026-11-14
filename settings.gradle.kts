// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    plugins {
        id("org.jetbrains.changelog") version "2.5.0"
        id("org.jetbrains.kotlin.jvm") version "2.4.21"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.20.1"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

check(gradle.gradleVersion == "9.9.1") { "Seq MCP builds require an installed Gradle 9.9.1." }
rootProject.name = "seq-mcp-plugin"

dependencyResolutionManagement.repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
