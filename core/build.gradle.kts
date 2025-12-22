plugins {
    kotlin("jvm")
}

description = "Flovyn SDK core - workflow and task definitions, workers, and client"

dependencies {
    api(project(":native"))

    // JNA for native library loading (used by uniffi-generated bindings)
    implementation("net.java.dev.jna:jna:5.14.0")

    // Kotlin coroutines
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}
