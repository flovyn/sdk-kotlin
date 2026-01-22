package ai.flovyn.sdk.e2e

import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.time.Duration
import java.util.*

/**
 * Test harness for E2E tests using Testcontainers.
 *
 * Provides container orchestration for PostgreSQL, NATS, and Flovyn server,
 * using static API key authentication.
 *
 * All containers are started by the harness - no external dependencies required.
 *
 * Matches sdk-rust/worker-sdk/tests/e2e/harness.rs exactly.
 */
class TestHarness private constructor() {

    private val logger = LoggerFactory.getLogger(TestHarness::class.java)

    // Container instances
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var nats: GenericContainer<*>
    private lateinit var server: GenericContainer<*>
    private lateinit var configFile: File

    // Connection info
    var serverGrpcPort: Int = 0
        private set
    var serverHttpPort: Int = 0
        private set

    // Test org info (pre-configured via config file)
    // Matches Rust: format!("test-{}", &Uuid::new_v4().to_string()[..8])
    var orgId: UUID = UUID.randomUUID()
        private set
    var orgSlug: String = "test-${UUID.randomUUID().toString().substring(0, 8)}"
        private set

    // Matches Rust: format!("flovyn_sk_test_{}", &Uuid::new_v4().to_string()[..16])
    var apiKey: String = "flovyn_sk_test_${UUID.randomUUID().toString().substring(0, 16)}"
        private set

    // Matches Rust: format!("flovyn_wk_test_{}", &Uuid::new_v4().to_string()[..16])
    var workerToken: String = "flovyn_wk_test_${UUID.randomUUID().toString().substring(0, 16)}"
        private set

    /**
     * Start all containers and set up test org.
     */
    fun start() {
        logger.info("[HARNESS] Starting test harness containers...")

        // Create config file with org and static API keys
        // Use /tmp explicitly for macOS compatibility with Docker bind mounts
        // (macOS default temp dir /var/folders/... is not shared with Docker)
        configFile = File.createTempFile("flovyn-test-config-", ".toml", File("/tmp"))
        configFile.writeText(createConfigContent())
        configFile.deleteOnExit()
        logger.info("[HARNESS] Created config file with pre-configured org and API keys at: ${configFile.absolutePath}")

        // Start PostgreSQL container
        // Matches Rust: GenericImage::new("postgres", "18-alpine").with_wait_for(WaitFor::message_on_stderr("database system is ready to accept connections"))
        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("flovyn")
            .withUsername("flovyn")
            .withPassword("flovyn")
            .withLabel("flovyn-test", "true")

        postgres.start()
        val pgPort = postgres.firstMappedPort
        logger.info("PostgreSQL started on port $pgPort")

        // Start NATS container
        // Matches Rust: GenericImage::new("nats", "latest").with_wait_for(WaitFor::message_on_stderr("Server is ready"))
        nats = GenericContainer(DockerImageName.parse("nats:latest"))
            .withExposedPorts(4222)
            .withLabel("flovyn-test", "true")
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))

        nats.start()
        val natsPort = nats.firstMappedPort
        logger.info("NATS started on port $natsPort")

        // Start Flovyn Server container
        // Matches Rust configuration exactly
        // FLOVYN_SERVER_IMAGE should include the tag, e.g., rg.fr-par.scw.cloud/flovyn/flovyn-server:main
        val serverImage = System.getenv("FLOVYN_SERVER_IMAGE") ?: "rg.fr-par.scw.cloud/flovyn/flovyn-server:latest"
        val verboseLogging = System.getenv("FLOVYN_E2E_VERBOSE") == "1"

        server = GenericContainer(DockerImageName.parse(serverImage))
            .withExposedPorts(8000, 9090)
            .withLabel("flovyn-test", "true")
            // Add host.docker.internal mapping for Linux (required for container to reach host ports)
            .withExtraHost("host.docker.internal", "host-gateway")
            .withEnv("DATABASE_URL", "postgres://flovyn:flovyn@host.docker.internal:$pgPort/flovyn")
            .withEnv("NATS__ENABLED", "true")
            .withEnv("NATS__URL", "nats://host.docker.internal:$natsPort")
            .withEnv("SERVER_PORT", "8000")
            .withEnv("GRPC_SERVER_PORT", "9090")
            // Copy config file to container (works with Podman, unlike bind mounts)
            .withCopyFileToContainer(MountableFile.forHostPath(configFile.absolutePath), "/app/config.toml")
            .withEnv("CONFIG_FILE", "/app/config.toml")
            .withStartupTimeout(Duration.ofSeconds(120))
            .apply {
                if (verboseLogging) {
                    withLogConsumer(Slf4jLogConsumer(logger).withPrefix("flovyn-server"))
                }
            }

        server.start()
        serverHttpPort = server.getMappedPort(8000)
        serverGrpcPort = server.getMappedPort(9090)
        logger.info("Flovyn server started - HTTP: $serverHttpPort, gRPC: $serverGrpcPort")

        // Wait for server health (30s timeout)
        waitForHealth()

        logger.info("[HARNESS] Test harness ready - org: $orgSlug")
    }

    /**
     * Stop all containers.
     */
    fun stop() {
        val keepContainers = System.getenv("FLOVYN_TEST_KEEP_CONTAINERS") != null
        if (keepContainers) {
            logger.info("[HARNESS] FLOVYN_TEST_KEEP_CONTAINERS set - skipping cleanup")
            return
        }

        logger.info("[HARNESS] Stopping containers...")
        runCatching { server.stop() }
        runCatching { nats.stop() }
        runCatching { postgres.stop() }
        runCatching { configFile.delete() }
        logger.info("[HARNESS] Cleanup complete")
    }

    /**
     * Wait for server health endpoint to respond (max 30 seconds).
     * Matches Rust: wait_for_health function
     */
    private fun waitForHealth() {
        val url = "http://localhost:$serverHttpPort/_/health"
        val maxAttempts = 15
        val delayMs = 2000L

        repeat(maxAttempts) { i ->
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    logger.info("Server is healthy after ${i * 2} seconds")
                    return
                }
                logger.info("Health check returned: ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                // Connection refused - server not ready yet
            }
            Thread.sleep(delayMs)
        }

        throw RuntimeException(
            "Server health check timed out after 30 seconds.\nCheck logs with: docker logs ${server.containerId}",
        )
    }

    /**
     * Create config content with org and static API key configuration.
     * Matches Rust create_config_file function exactly (including leading newline).
     */
    private fun createConfigContent(): String = """
# Pre-configured organizations
[[orgs]]
id = "$orgId"
name = "Test Organization"
slug = "$orgSlug"
tier = "FREE"

# Authentication configuration
[auth]
enabled = true

# Static API keys
[auth.static_api_key]
keys = [
    { key = "$apiKey", org_id = "$orgId", principal_type = "User", principal_id = "api:test", role = "ADMIN" },
    { key = "$workerToken", org_id = "$orgId", principal_type = "Worker", principal_id = "worker:test" }
]

# Endpoint authentication
[auth.endpoints.http]
authenticators = ["static_api_key"]
authorizer = "cedar"

[auth.endpoints.grpc]
authenticators = ["static_api_key"]
authorizer = "cedar"
"""

    companion object {
        @Volatile
        private var instance: TestHarness? = null
        private val instanceLogger = LoggerFactory.getLogger("TestHarness.Companion")

        /**
         * Get the singleton test harness instance.
         * Starts containers on first access.
         */
        @Synchronized
        fun getInstance(): TestHarness {
            val existing = instance
            if (existing != null) {
                val httpPort = existing.serverHttpPort
                val grpcPort = existing.serverGrpcPort
                instanceLogger.info("[HARNESS] Reusing existing harness (HTTP=$httpPort, gRPC=$grpcPort)")
                return existing
            }

            instanceLogger.info("[HARNESS] Creating NEW harness instance...")
            return TestHarness().also {
                it.start()
                instance = it
                instanceLogger.info(
                    "[HARNESS] New harness created (ports: HTTP=${it.serverHttpPort}, gRPC=${it.serverGrpcPort})",
                )

                // Register shutdown hook for cleanup
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        it.stop()
                    },
                )
            }
        }
    }
}
