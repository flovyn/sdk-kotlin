plugins {
    kotlin("jvm") version "1.9.22" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    `maven-publish`
    signing
}

allprojects {
    group = "ai.flovyn"
    version = rootProject.findProperty("version") as String? ?: "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Modules to publish to Maven Central
val publishableModules = setOf("worker-native", "worker-sdk", "worker-sdk-jackson")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Apply publishing only to publishable modules
    if (name in publishableModules) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        configure<JavaPluginExtension> {
            withJavadocJar()
            withSourcesJar()
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    artifactId = project.name
                    from(components["java"])

                    pom {
                        name.set("Flovyn ${project.name}")
                        description.set(project.description ?: "Flovyn Kotlin SDK")
                        url.set("https://github.com/flovyn/sdk-kotlin")

                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }

                        developers {
                            developer {
                                id.set("flovyn")
                                name.set("Flovyn Team")
                                email.set("team@flovyn.ai")
                            }
                        }

                        scm {
                            connection.set("scm:git:git://github.com/flovyn/sdk-kotlin.git")
                            developerConnection.set("scm:git:ssh://github.com:flovyn/sdk-kotlin.git")
                            url.set("https://github.com/flovyn/sdk-kotlin")
                        }
                    }
                }
            }

            repositories {
                maven {
                    name = "CentralPortal"
                    url = if (version.toString().endsWith("SNAPSHOT")) {
                        uri("https://central.sonatype.com/repository/maven-snapshots/")
                    } else {
                        uri("https://central.sonatype.com/api/v1/publisher/upload")
                    }

                    credentials {
                        username = findProperty("centralUsername") as String?
                            ?: System.getenv("MAVEN_CENTRAL_USERNAME")
                        password = findProperty("centralPassword") as String?
                            ?: System.getenv("MAVEN_CENTRAL_PASSWORD")
                    }
                }
            }
        }

        configure<SigningExtension> {
            val signingKeyId: String? = findProperty("signing.keyId") as String?
            val signingKey: String? = findProperty("signing.key") as String?
            val signingPassword: String? = findProperty("signing.password") as String?

            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword ?: "")
                sign(the<PublishingExtension>().publications["mavenJava"])
            }
        }

        tasks.withType<Sign>().configureEach {
            onlyIf { project.hasProperty("signing.key") }
        }
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.1.1")
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        filter {
            exclude("**/generated/**")
            exclude("**/uniffi/**")
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    dependencies {
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = listOf("-Xjsr305=strict")
        }
    }
}
