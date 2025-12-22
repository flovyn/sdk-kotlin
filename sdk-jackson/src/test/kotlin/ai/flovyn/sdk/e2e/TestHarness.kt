package ai.flovyn.sdk.e2e

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/**
 * Test harness for E2E tests using Testcontainers.
 *
 * Manages the complete container stack:
 * - PostgreSQL for database
 * - NATS for messaging
 * - Flovyn Server
 *
 * Mirrors the Rust SDK test harness pattern.
 */
class TestHarness private constructor() {

    private val logger = LoggerFactory.getLogger(TestHarness::class.java)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val network = Network.newNetwork()

    // Container instances
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var nats: GenericContainer<*>
    private lateinit var server: GenericContainer<*>

    // Connection info
    var serverGrpcPort: Int = 0
        private set
    var serverHttpPort: Int = 0
        private set

    // Test tenant info
    var tenantId: UUID = UUID.randomUUID()
        private set
    var tenantSlug: String = ""
        private set
    var workerToken: String = ""
        private set

    /**
     * Start all containers and set up test tenant.
     */
    fun start() {
        logger.info("Starting test harness containers...")

        // Start PostgreSQL
        postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withDatabaseName("flovyn")
            .withUsername("flovyn")
            .withPassword("flovyn")
            .withLabel("flovyn-test", "true")

        postgres.start()
        logger.info("PostgreSQL started on port ${postgres.firstMappedPort}")

        // Start NATS
        nats = GenericContainer(DockerImageName.parse("nats:latest"))
            .withNetwork(network)
            .withNetworkAliases("nats")
            .withExposedPorts(4222)
            .withLabel("flovyn-test", "true")
            .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))

        nats.start()
        logger.info("NATS started on port ${nats.firstMappedPort}")

        // Start Flovyn Server
        val serverImage = System.getenv("FLOVYN_SERVER_IMAGE") ?: "flovyn-server-test:latest"
        server = GenericContainer(DockerImageName.parse(serverImage))
            .withNetwork(network)
            .withNetworkAliases("flovyn-server")
            .withExposedPorts(8000, 9090)
            .withLabel("flovyn-test", "true")
            .withEnv("DATABASE_URL", "postgres://flovyn:flovyn@postgres:5432/flovyn")
            .withEnv("NATS_URL", "nats://nats:4222")
            .withEnv("FLOVYN_SECURITY_ENABLED", "true")
            .withEnv("JWT_SKIP_SIGNATURE_VERIFICATION", "true")
            .withEnv("RUST_LOG", "info")
            .withStartupTimeout(Duration.ofSeconds(120))
            .withLogConsumer(Slf4jLogConsumer(logger).withPrefix("flovyn-server"))

        server.start()
        serverHttpPort = server.getMappedPort(8000)
        serverGrpcPort = server.getMappedPort(9090)
        logger.info("Flovyn Server started - HTTP: $serverHttpPort, gRPC: $serverGrpcPort")

        // Wait for server health
        waitForHealth()

        // Set up test tenant
        setupTestTenant()

        logger.info("Test harness ready - tenant: $tenantSlug")
    }

    /**
     * Stop all containers.
     */
    fun stop() {
        val keepContainers = System.getProperty("FLOVYN_TEST_KEEP_CONTAINERS", "0") == "1"
        if (keepContainers) {
            logger.info("Keeping containers running (FLOVYN_TEST_KEEP_CONTAINERS=1)")
            return
        }

        logger.info("Stopping test harness containers...")
        runCatching { server.stop() }
        runCatching { nats.stop() }
        runCatching { postgres.stop() }
        runCatching { network.close() }
        logger.info("Test harness stopped")
    }

    /**
     * Wait for server health endpoint to respond.
     */
    private fun waitForHealth() {
        val healthUrl = "http://localhost:$serverHttpPort/_/health"
        val maxAttempts = 30
        val delayMs = 2000L

        repeat(maxAttempts) { attempt ->
            try {
                val request = Request.Builder()
                    .url(healthUrl)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        logger.info("Server health check passed after ${attempt + 1} attempts")
                        return
                    }
                }
            } catch (e: Exception) {
                logger.debug("Health check attempt ${attempt + 1} failed: ${e.message}")
            }
            Thread.sleep(delayMs)
        }

        throw RuntimeException("Server health check failed after $maxAttempts attempts. Check Docker logs.")
    }

    /**
     * Set up test tenant with JWT authentication.
     */
    private fun setupTestTenant() {
        // Generate JWT for test user
        val jwt = generateTestJwt()

        // Create tenant
        val slug = "test-${UUID.randomUUID().toString().take(8)}"
        val createTenantBody = """
            {
                "name": "E2E Test Tenant",
                "slug": "$slug",
                "tier": "FREE",
                "region": "us-west-2"
            }
        """.trimIndent()

        val createTenantRequest = Request.Builder()
            .url("http://localhost:$serverHttpPort/api/tenants")
            .header("Authorization", "Bearer $jwt")
            .header("Content-Type", "application/json")
            .post(createTenantBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(createTenantRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Failed to create tenant: ${response.code} ${response.body?.string()}")
            }
            val body = response.body?.string() ?: "{}"
            // Parse tenant ID from response
            val idMatch = """"id"\s*:\s*"([^"]+)"""".toRegex().find(body)
            tenantId = UUID.fromString(idMatch?.groupValues?.get(1) ?: throw RuntimeException("No tenant ID in response"))
            tenantSlug = slug
        }

        logger.info("Created test tenant: $tenantSlug ($tenantId)")

        // Create worker token
        val createTokenBody = """
            {
                "displayName": "E2E Test Worker Token"
            }
        """.trimIndent()

        val createTokenRequest = Request.Builder()
            .url("http://localhost:$serverHttpPort/api/tenants/$tenantSlug/worker-tokens")
            .header("Authorization", "Bearer $jwt")
            .header("Content-Type", "application/json")
            .post(createTokenBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(createTokenRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("Failed to create worker token: ${response.code} ${response.body?.string()}")
            }
            val body = response.body?.string() ?: "{}"
            val tokenMatch = """"token"\s*:\s*"([^"]+)"""".toRegex().find(body)
            workerToken = tokenMatch?.groupValues?.get(1) ?: throw RuntimeException("No token in response")
        }

        logger.info("Created worker token: ${workerToken.take(10)}...")
    }

    /**
     * Generate a test JWT.
     * Since the server has JWT_SKIP_SIGNATURE_VERIFICATION=true, we can use a simple HMAC key.
     */
    private fun generateTestJwt(): String {
        val now = Instant.now()
        val userId = "test-user-${UUID.randomUUID().toString().take(8).uppercase()}"

        // Use a simple HMAC key since signature verification is skipped
        val secretKey: SecretKey = Keys.hmacShaKeyFor(
            "test-secret-key-for-e2e-tests-minimum-256-bits-required-here".toByteArray()
        )

        return Jwts.builder()
            .subject(userId)
            .claim("id", userId)
            .claim("name", "E2E Test User")
            .claim("email", "e2e-test@example.com")
            .issuer("http://localhost:3000")
            .audience().add("flovyn-server").and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(3600)))
            .signWith(secretKey)
            .compact()
    }

    companion object {
        @Volatile
        private var instance: TestHarness? = null

        /**
         * Get the singleton test harness instance.
         * Starts containers on first access.
         */
        @Synchronized
        fun getInstance(): TestHarness {
            return instance ?: TestHarness().also {
                it.start()
                instance = it

                // Register shutdown hook
                Runtime.getRuntime().addShutdownHook(Thread {
                    it.stop()
                })
            }
        }
    }
}
