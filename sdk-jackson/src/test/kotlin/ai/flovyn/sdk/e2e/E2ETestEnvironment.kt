package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.client.FlovynClient
import ai.flovyn.sdk.client.FlovynClientBuilder
import ai.flovyn.sdk.task.TaskDefinition
import ai.flovyn.sdk.workflow.WorkflowDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * E2E test environment providing utilities for workflow testing.
 *
 * Mirrors the Rust SDK E2ETestEnvironment pattern.
 */
class E2ETestEnvironment internal constructor(
    private val harness: TestHarness,
    private val taskQueue: String,
    val client: FlovynClient
) {

    private val logger = LoggerFactory.getLogger(E2ETestEnvironment::class.java)

    /**
     * Start the worker.
     */
    suspend fun startWorker() {
        client.start()
        // Wait for worker registration
        delay(WORKER_REGISTRATION_DELAY.inWholeMilliseconds)
        logger.info("Worker started for queue: $taskQueue")
    }

    /**
     * Start a workflow execution.
     */
    suspend fun startWorkflow(
        workflowKind: String,
        input: Any? = null
    ): UUID {
        return client.startWorkflow(workflowKind, input)
    }

    /**
     * Start a workflow and wait for completion.
     */
    suspend fun startAndAwait(
        workflowKind: String,
        input: Any? = null,
        timeout: Duration = DEFAULT_AWAIT_TIMEOUT
    ): UUID {
        val executionId = startWorkflow(workflowKind, input)
        awaitCompletion(executionId, timeout)
        return executionId
    }

    /**
     * Wait for workflow completion.
     */
    suspend fun awaitCompletion(
        executionId: UUID,
        timeout: Duration = DEFAULT_AWAIT_TIMEOUT
    ) {
        withTimeout(timeout) {
            // Poll for completion
            // Note: In a full implementation, this would query the server for workflow status
            // For now, we just wait a reasonable time
            delay(timeout.inWholeMilliseconds / 2)
        }
    }

    /**
     * Stop the environment.
     */
    fun stop() {
        client.stop()
        logger.info("E2E test environment stopped")
    }

    companion object {
        val DEFAULT_AWAIT_TIMEOUT = 30.seconds
        val WORKER_REGISTRATION_DELAY = 2.seconds
        val TEST_TIMEOUT = 60.seconds

        /**
         * Create a builder for the test environment.
         */
        fun builder(): E2ETestEnvBuilder = E2ETestEnvBuilder()
    }
}

/**
 * Builder for E2ETestEnvironment.
 */
class E2ETestEnvBuilder {
    private val harness = TestHarness.getInstance()
    private val taskQueue = "test-queue-${UUID.randomUUID()}"
    @PublishedApi
    internal val clientBuilder: FlovynClientBuilder = FlovynClient.builder()
        .serverAddress("localhost", harness.serverGrpcPort)
        .workerToken(harness.workerToken)
        .tenantId(harness.tenantId)
        .taskQueue(taskQueue)

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
     * Set the task queue.
     */
    fun taskQueue(queue: String) = apply {
        clientBuilder.taskQueue(queue)
    }

    /**
     * Build the environment.
     */
    fun build(): E2ETestEnvironment {
        val client = clientBuilder.build()
        return E2ETestEnvironment(harness, taskQueue, client)
    }

    /**
     * Build the environment and start the worker.
     */
    suspend fun buildAndStart(): E2ETestEnvironment {
        val env = build()
        env.startWorker()
        return env
    }
}
