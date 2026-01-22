package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.client.FlovynClient
import ai.flovyn.sdk.client.FlovynClientBuilder
import ai.flovyn.sdk.task.TaskDefinition
import ai.flovyn.sdk.workflow.WorkflowDefinition
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Result of a workflow execution.
 */
data class WorkflowResult(
    val workflowId: UUID,
    val status: WorkflowStatus,
    val output: Map<String, Any?>?,
    val error: String?
)

enum class WorkflowStatus {
    COMPLETED,
    FAILED,
    PENDING
}

/**
 * E2E test environment providing utilities for workflow testing.
 *
 * Mirrors the Rust SDK E2ETestEnvironment pattern with proper result assertions.
 */
class E2ETestEnvironment internal constructor(
    private val harness: TestHarness,
    private val queue: String,
    val client: FlovynClient,
    private val testPrefix: String = ""
) {

    /**
     * Generate a unique workflow kind using the test prefix.
     * @param baseKind The base workflow kind (e.g., "echo-workflow")
     * @return Prefixed kind if testPrefix is set, otherwise baseKind unchanged
     */
    fun workflowKind(baseKind: String): String =
        if (testPrefix.isNotEmpty()) "$baseKind:$testPrefix" else baseKind

    /**
     * Generate a unique task kind using the test prefix.
     * @param baseKind The base task kind (e.g., "add-task")
     * @return Prefixed kind if testPrefix is set, otherwise baseKind unchanged
     */
    fun taskKind(baseKind: String): String =
        if (testPrefix.isNotEmpty()) "$baseKind:$testPrefix" else baseKind

    private val logger = LoggerFactory.getLogger(E2ETestEnvironment::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    /**
     * Start the worker.
     */
    suspend fun startWorker() {
        logger.info("[ENV] Starting worker for queue: $queue")
        client.start()
        logger.info("[ENV] Worker started, waiting for ready status...")
        // Wait for worker to be ready
        client.awaitReady()
        // Give the server time to process worker registration (matching Rust SDK's WORKER_REGISTRATION_DELAY)
        delay(WORKER_REGISTRATION_DELAY.inWholeMilliseconds)
        logger.info("[ENV] Worker ready! Status: ${client.workerStatus}, Queue: $queue")
    }

    /**
     * Start a workflow execution.
     */
    suspend fun startWorkflow(
        workflowKind: String,
        input: Any? = null
    ): UUID {
        logger.info("[ENV] Starting workflow kind=$workflowKind on queue=$queue")
        val id = client.startWorkflow(workflowKind, input)
        logger.info("[ENV] Workflow started: id=$id")
        return id
    }

    /**
     * Start a workflow and wait for completion, returning the result.
     */
    suspend fun startAndAwait(
        workflowKind: String,
        input: Any? = null,
        timeout: Duration = DEFAULT_AWAIT_TIMEOUT
    ): WorkflowResult {
        val executionId = startWorkflow(workflowKind, input)
        return awaitCompletion(executionId, timeout)
    }

    /**
     * Wait for workflow completion and return the result.
     * Polls HTTP API for workflow events until completion or failure is detected.
     */
    suspend fun awaitCompletion(
        executionId: UUID,
        timeout: Duration = DEFAULT_AWAIT_TIMEOUT
    ): WorkflowResult {
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Check for completion using FFI gRPC
            try {
                val ffiEvents = client.getWorkflowEvents(executionId)
                for (ffiEvent in ffiEvents) {
                    when (ffiEvent.eventType) {
                        "WORKFLOW_COMPLETED" -> {
                            // Parse the JSON payload to extract output
                            val payloadStr = String(ffiEvent.payload)
                            @Suppress("UNCHECKED_CAST")
                            val payload = if (payloadStr.isNotBlank()) {
                                objectMapper.readValue(payloadStr, Map::class.java) as? Map<String, Any?>
                            } else null
                            val output = payload?.get("output") as? Map<String, Any?>
                            logger.info("Workflow {} completed via gRPC", executionId)
                            return WorkflowResult(
                                workflowId = executionId,
                                status = WorkflowStatus.COMPLETED,
                                output = output,
                                error = null
                            )
                        }
                        "WORKFLOW_EXECUTION_FAILED", "WORKFLOW_FAILED" -> {
                            val payloadStr = String(ffiEvent.payload)
                            @Suppress("UNCHECKED_CAST")
                            val payload = if (payloadStr.isNotBlank()) {
                                objectMapper.readValue(payloadStr, Map::class.java) as? Map<String, Any?>
                            } else null
                            val error = payload?.get("error") as? String ?: "Unknown error"
                            logger.info("Workflow {} failed via gRPC: {}", executionId, error)
                            return WorkflowResult(
                                workflowId = executionId,
                                status = WorkflowStatus.FAILED,
                                output = null,
                                error = error
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("Error fetching events via gRPC: {}", e.message)
            }

            // Poll interval
            delay(500)
        }

        logger.warn("Workflow {} timed out after {}ms", executionId, timeoutMs)
        return WorkflowResult(
            workflowId = executionId,
            status = WorkflowStatus.PENDING,
            output = null,
            error = "Timeout after ${timeout}"
        )
    }

    // HTTP-based fetchWorkflowEvents removed - using FFI gRPC-based getWorkflowEvents instead

    // Lifecycle properties (exposing FlovynClient properties)

    /**
     * Check if the worker has started.
     */
    val isStarted: Boolean
        get() = client.isRunning || client.workerStatus != "not_started"

    /**
     * Get the current worker status.
     */
    val workerStatus: String
        get() = client.workerStatus

    /**
     * Check if the worker is currently running.
     */
    val isRunning: Boolean
        get() = client.isRunning

    /**
     * Get the time when the worker started, in milliseconds since Unix epoch.
     */
    val workerStartedAtMs: Long
        get() = client.workerStartedAtMs

    /**
     * Get the worker uptime in milliseconds.
     */
    val workerUptimeMs: Long
        get() = client.workerUptimeMs

    /**
     * Get the server-assigned worker ID.
     */
    val workerId: String?
        get() = client.workerId

    /**
     * Get the maximum concurrent workflows setting.
     */
    val maxConcurrentWorkflows: Int
        get() = client.maxConcurrentWorkflows

    /**
     * Get the maximum concurrent tasks setting.
     */
    val maxConcurrentTasks: Int
        get() = client.maxConcurrentTasks

    /**
     * Check if a workflow kind is registered.
     */
    fun hasWorkflow(kind: String): Boolean = client.hasWorkflow(kind)

    /**
     * Check if a task kind is registered.
     */
    fun hasTask(kind: String): Boolean = client.hasTask(kind)

    /**
     * Stop the environment.
     */
    fun stop() {
        client.stop()
        logger.info("E2E test environment stopped")
    }

    companion object {
        val DEFAULT_AWAIT_TIMEOUT = 30.seconds
        val WORKER_REGISTRATION_DELAY = 3.seconds
        val TEST_TIMEOUT = 60.seconds

        /**
         * Create a builder with auto-generated unique queue and test prefix.
         * This is the recommended way to create test environments.
         *
         * @param testName Name of the test class (for debugging/logging)
         */
        fun builder(testName: String): E2ETestEnvBuilder = E2ETestEnvBuilder.unique(testName)

        /**
         * Create a builder with default settings (for backwards compatibility).
         */
        fun builder(): E2ETestEnvBuilder = E2ETestEnvBuilder.create()
    }
}

/**
 * Builder for E2ETestEnvironment.
 *
 * Supports unique workflow/task kinds per test to prevent conflicts when tests run in parallel
 * or share the same server. Mirrors the Rust SDK E2ETestEnvBuilder pattern.
 *
 * Usage:
 * ```kotlin
 * val env = E2ETestEnvironment.builder("MyTest")
 *     .registerWorkflow(EchoWorkflow(kindSuffix))
 *     .buildAndStart()
 *
 * // Start workflow with prefixed kind
 * env.startWorkflow(env.workflowKind("echo-workflow"), input)
 * ```
 */
class E2ETestEnvBuilder private constructor(
    private val testName: String,
    private val testId: UUID
) {
    private val harness = TestHarness.getInstance()

    /**
     * Unique prefix for this test environment.
     * Format: "{testName}:{testId}" e.g., "WorkflowE2ETest:550e8400-..."
     */
    val testPrefix: String = "$testName:${testId.toString().substring(0, 8)}"

    /**
     * Unique queue for this test environment.
     */
    val queue: String = "q:$testPrefix"

    @PublishedApi
    internal val clientBuilder: FlovynClientBuilder = FlovynClientBuilder()
        .serverAddress("localhost", harness.serverGrpcPort)
        .workerToken(harness.workerToken)
        .orgId(harness.orgId)
        .queue(queue)

    /**
     * Generate a unique workflow kind using the test prefix.
     * @param baseKind The base workflow kind (e.g., "echo-workflow")
     * @return Prefixed kind (e.g., "echo-workflow:WorkflowE2ETest:550e8400")
     */
    fun workflowKind(baseKind: String): String = "$baseKind:$testPrefix"

    /**
     * Generate a unique task kind using the test prefix.
     * @param baseKind The base task kind (e.g., "add-task")
     * @return Prefixed kind (e.g., "add-task:WorkflowE2ETest:550e8400")
     */
    fun taskKind(baseKind: String): String = "$baseKind:$testPrefix"

    /**
     * Register a workflow definition.
     */
    inline fun <reified INPUT, reified OUTPUT> registerWorkflow(
        workflow: WorkflowDefinition<INPUT, OUTPUT>
    ) = apply {
        clientBuilder.registerWorkflow(workflow)
    }

    /**
     * Register a task definition.
     */
    inline fun <reified INPUT, reified OUTPUT> registerTask(
        task: TaskDefinition<INPUT, OUTPUT>
    ) = apply {
        clientBuilder.registerTask(task)
    }

    /**
     * Set the task queue (overrides the auto-generated unique queue).
     */
    fun queue(queue: String) = apply {
        clientBuilder.queue(queue)
    }

    /**
     * Build the environment.
     */
    fun build(): E2ETestEnvironment {
        val client = clientBuilder.build()
        return E2ETestEnvironment(harness, queue, client, testPrefix)
    }

    /**
     * Build the environment and start the worker.
     */
    suspend fun buildAndStart(): E2ETestEnvironment {
        val env = build()
        env.startWorker()
        return env
    }

    companion object {
        /**
         * Create a builder with auto-generated unique queue and test prefix.
         * This ensures test isolation when running tests in parallel.
         *
         * @param testName Name of the test class or test (for debugging/logging)
         */
        fun unique(testName: String): E2ETestEnvBuilder {
            return E2ETestEnvBuilder(testName, UUID.randomUUID())
        }

        /**
         * Create a builder with default settings (for backwards compatibility).
         * Uses "default" as the test name.
         */
        fun create(): E2ETestEnvBuilder {
            return unique("default")
        }
    }
}
