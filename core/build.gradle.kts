// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure Kotlin/JVM domain module — zero Android imports allowed (see CLAUDE.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// P1 acceptance: ≥90% line coverage on :core, enforced by `koverVerify` in CI.
kover {
    reports {
        verify {
            rule {
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
        }
    }
}
