plugins {
    kotlin("jvm")
}

description = "Flovyn SDK with Jackson serialization"

dependencies {
    api(project(":core"))

    // Jackson for JSON serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")

    // Testing
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")

    // SLF4J for logging in tests
    testImplementation("org.slf4j:slf4j-simple:2.0.9")

    // HTTP client for health checks
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JWT for test authentication
    testImplementation("io.jsonwebtoken:jjwt-api:0.12.3")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")
}

// E2E tests need Docker and are run separately
tasks.test {
    useJUnitPlatform()

    // Exclude E2E tests from regular test run
    exclude("**/e2e/**")
}

// Separate task for E2E tests
tasks.register<Test>("e2eTest") {
    useJUnitPlatform()

    // Only include E2E tests
    include("**/e2e/**")

    // Run tests sequentially to share containers
    maxParallelForks = 1

    // Show stdout/stderr from tests
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed", "standardOut", "standardError")
    }

    // Pass environment variables
    systemProperty("FLOVYN_E2E_USE_DEV_INFRA", System.getenv("FLOVYN_E2E_USE_DEV_INFRA") ?: "1")
    systemProperty("FLOVYN_TEST_KEEP_CONTAINERS", System.getenv("FLOVYN_TEST_KEEP_CONTAINERS") ?: "0")
}
