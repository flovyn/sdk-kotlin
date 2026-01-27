plugins {
    kotlin("jvm") version "1.9.22" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("org.jreleaser") version "1.15.0" apply false
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
        apply(plugin = "org.jreleaser")

        configure<JavaPluginExtension> {
            withJavadocJar()
            withSourcesJar()
        }

        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    groupId = "ai.flovyn"
                    artifactId = "${project.name}-kotlin"
                    from(components["java"])

                    pom {
                        name.set("Flovyn ${project.name}")
                        description.set(project.description ?: "Flovyn Kotlin SDK")
                        url.set("https://github.com/flovyn/sdk-kotlin")
                        inceptionYear.set("2024")

                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://spdx.org/licenses/Apache-2.0.html")
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
                            connection.set("scm:git:https://github.com/flovyn/sdk-kotlin.git")
                            developerConnection.set("scm:git:ssh://github.com/flovyn/sdk-kotlin.git")
                            url.set("https://github.com/flovyn/sdk-kotlin")
                        }
                    }
                }
            }

            repositories {
                // Staging for JReleaser (releases)
                maven {
                    name = "staging"
                    url = uri(layout.buildDirectory.dir("staging-deploy"))
                }
                // Direct snapshot publishing
                maven {
                    name = "snapshot"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                    credentials {
                        username = findProperty("mavenCentralUsername") as String?
                            ?: System.getenv("MAVEN_CENTRAL_USERNAME")
                        password = findProperty("mavenCentralPassword") as String?
                            ?: System.getenv("MAVEN_CENTRAL_PASSWORD")
                    }
                }
            }
        }

        configure<org.jreleaser.gradle.plugin.JReleaserExtension> {
            project {
                copyright.set("Flovyn")
            }

            signing {
                active.set(org.jreleaser.model.Active.ALWAYS)
                armored.set(true)
            }

            deploy {
                maven {
                    mavenCentral {
                        create("sonatype") {
                            active.set(org.jreleaser.model.Active.ALWAYS)
                            url.set("https://central.sonatype.com/api/v1/publisher")
                            stagingRepository(layout.buildDirectory.dir("staging-deploy").get().asFile.absolutePath)
                            // Don't wait for publishing to complete (fire-and-forget)
                            // Will still fail if upload/validation fails
                            skipPublicationCheck.set(true)
                        }
                    }
                }
            }
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
