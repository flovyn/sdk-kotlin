plugins {
    kotlin("jvm")
}

description = "Core bridge layer wrapping Flovyn FFI"

dependencies {
    api(project(":native"))

    // JNA for native library loading (used by uniffi-generated bindings)
    implementation("net.java.dev.jna:jna:5.14.0")
}
