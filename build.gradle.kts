// SPDX-License-Identifier: AGPL-3.0-or-later
// Plugins are declared once here (apply false) so all modules share one
// classloader for the Kotlin and Android Gradle plugins.
//
// `-Pquest.jvmOnly=true` builds only the pure-JVM modules (:core, :protocol,
// :server) without the Android toolchain — handy for server contributors and
// for machines without the Android SDK. settings.gradle.kts drops :data/:app.
buildscript {
    if (project.findProperty("quest.jvmOnly") != "true") {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:${libs.versions.agp.get()}")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
}
