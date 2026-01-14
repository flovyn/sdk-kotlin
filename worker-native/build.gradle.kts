plugins {
    kotlin("jvm")
}

description = "Native library loader for Flovyn FFI"

dependencies {
    // JNA for native library loading (used by uniffi-generated bindings)
    api("net.java.dev.jna:jna:5.14.0")
}

// Handle duplicate resources
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
