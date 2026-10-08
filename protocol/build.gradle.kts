// SPDX-License-Identifier: AGPL-3.0-or-later
// Wire DTOs shared by the app and the server, plus JSON Schema emission.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    api(project(":core"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// Regenerates /schema/*.json. `ProtocolSchemaTest` fails when the committed
// files drift from the DTOs, so run this after changing any wire type.
val generateSchemas by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Writes JSON Schema files for every wire DTO to /schema."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.dreamweave.quest.protocol.schema.SchemaMainKt")
    args(rootProject.layout.projectDirectory.dir("schema").asFile.absolutePath)
}

tasks.test {
    systemProperty("schemaDir", rootProject.layout.projectDirectory.dir("schema").asFile.absolutePath)
}

// Full stack traces of failing tests in the console (CI logs are the only window into them).
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
