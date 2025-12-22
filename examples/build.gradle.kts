plugins {
    kotlin("jvm")
    application
}

description = "Example applications using Flovyn SDK"

dependencies {
    implementation(project(":sdk-jackson"))
}

// Make all example classes runnable
application {
    mainClass.set("io.flovyn.examples.HelloWorldKt")
}

// Register additional run tasks for each example
tasks.register<JavaExec>("runHelloWorld") {
    group = "application"
    description = "Run the HelloWorld example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.flovyn.examples.HelloWorldKt")
}

tasks.register<JavaExec>("runOrderProcessing") {
    group = "application"
    description = "Run the OrderProcessing example"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.flovyn.examples.OrderProcessingKt")
}
