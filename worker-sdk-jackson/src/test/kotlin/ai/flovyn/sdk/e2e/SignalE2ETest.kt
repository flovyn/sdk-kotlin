package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for signal functionality.
 *
 * These tests require Docker and a running Flovyn server stack.
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignalE2ETest {

    private lateinit var env: E2ETestEnvironment
    private lateinit var testPrefix: String

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        val builder = E2ETestEnvironment.builder("SignalE2ETest")
        testPrefix = builder.testPrefix

        env = builder
            .registerWorkflow(SignalWorkflow(testPrefix))
            .registerWorkflow(MultiSignalWorkflow(testPrefix))
            .registerWorkflow(SignalCheckWorkflow(testPrefix))
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    @Test
    fun `test signal with start new workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val workflowId = "signal-test-${System.currentTimeMillis()}"

            // Use signal_with_start to create workflow with signal
            val result = env.signalWithStartWorkflow(
                workflowId = workflowId,
                workflowKind = env.workflowKind("signal-workflow"),
                input = SignalWorkflowInput(),
                signalName = "greeting",
                signalValue = mapOf("message" to "Hello from signal!"),
            )

            assertTrue(result.workflowCreated, "Workflow should have been created")
            assertNotNull(result.workflowExecutionId)

            // Wait for workflow to complete
            val workflowResult = env.awaitCompletion(result.workflowExecutionId, 30.seconds)
            assertEquals(WorkflowStatus.COMPLETED, workflowResult.status)

            // Verify the output contains the signal
            val output = workflowResult.output
            assertNotNull(output)
            assertEquals("greeting", output["signalName"])
        }
    }

    @Test
    fun `test signal existing workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Start the workflow (it will suspend waiting for signal)
            val executionId = env.startWorkflow(
                workflowKind = env.workflowKind("signal-workflow"),
                input = SignalWorkflowInput(),
            )

            // Wait for workflow to suspend
            delay(2000)

            // Send signal to the workflow
            val signalSeq = env.signalWorkflow(
                workflowId = executionId,
                signalName = "user-action",
                value = mapOf("action" to "approve", "user" to "admin"),
            )

            assertTrue(signalSeq > 0, "Signal sequence should be positive")

            // Wait for workflow to complete
            val result = env.awaitCompletion(executionId, 30.seconds)
            assertEquals(WorkflowStatus.COMPLETED, result.status)

            // Verify the output contains the signal
            val output = result.output
            assertNotNull(output)
            assertEquals("user-action", output["signalName"])
        }
    }

    @Test
    fun `test multiple signals`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Start the workflow expecting 3 signals
            val executionId = env.startWorkflow(
                workflowKind = env.workflowKind("multi-signal-workflow"),
                input = MultiSignalInput(signalCount = 3),
            )

            // Wait for workflow to start and suspend
            delay(2000)

            // Send 3 signals
            for (i in 1..3) {
                env.signalWorkflow(
                    workflowId = executionId,
                    signalName = "message-$i",
                    value = mapOf("content" to "Message $i"),
                )
                delay(100) // Small delay between signals
            }

            // Wait for workflow to complete
            val result = env.awaitCompletion(executionId, 30.seconds)
            assertEquals(WorkflowStatus.COMPLETED, result.status)

            // Verify all signals were received
            val output = result.output
            assertNotNull(output)
            assertEquals(3, output["count"])

            @Suppress("UNCHECKED_CAST")
            val signals = output["signals"] as? List<*>
            assertNotNull(signals)
            assertEquals(3, signals.size)
        }
    }

    @Test
    fun `test signal with start existing workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val workflowId = "signal-existing-test-${System.currentTimeMillis()}"

            // First signal_with_start creates the workflow
            val result1 = env.signalWithStartWorkflow(
                workflowId = workflowId,
                workflowKind = env.workflowKind("multi-signal-workflow"),
                input = MultiSignalInput(signalCount = 2),
                signalName = "signal-1",
                signalValue = mapOf("seq" to 1),
            )

            assertTrue(result1.workflowCreated, "First call should create workflow")
            val executionId = result1.workflowExecutionId

            // Small delay
            delay(500)

            // Second signal_with_start to same workflow_id
            val result2 = env.signalWithStartWorkflow(
                workflowId = workflowId,
                workflowKind = env.workflowKind("multi-signal-workflow"),
                input = MultiSignalInput(signalCount = 2),
                signalName = "signal-2",
                signalValue = mapOf("seq" to 2),
            )

            assertFalse(result2.workflowCreated, "Second call should NOT create new workflow")
            assertEquals(executionId, result2.workflowExecutionId, "Should be same execution")

            // Wait for workflow to complete (it expects 2 signals)
            val result = env.awaitCompletion(executionId, 30.seconds)
            assertEquals(WorkflowStatus.COMPLETED, result.status)

            // Verify both signals were received
            val output = result.output
            assertNotNull(output)

            @Suppress("UNCHECKED_CAST")
            val signals = output["signals"] as? List<*>
            assertNotNull(signals)
            assertEquals(2, signals.size)
        }
    }

    @Test
    fun `test signal check and drain`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val workflowId = "signal-check-${System.currentTimeMillis()}"

            // Use signal_with_start to create workflow with initial signal
            val result = env.signalWithStartWorkflow(
                workflowId = workflowId,
                workflowKind = env.workflowKind("signal-check-workflow"),
                input = SignalCheckInput(),
                signalName = "initial",
                signalValue = mapOf("data" to "first"),
            )

            assertTrue(result.workflowCreated)

            // Send another signal immediately
            env.signalWorkflow(
                workflowId = result.workflowExecutionId,
                signalName = "second",
                value = mapOf("data" to "second"),
            )

            // Wait for workflow to complete
            val workflowResult = env.awaitCompletion(result.workflowExecutionId, 30.seconds)
            assertEquals(WorkflowStatus.COMPLETED, workflowResult.status)

            // Verify the output
            val output = workflowResult.output
            assertNotNull(output)
            assertEquals(true, output["hasSignal"])

            @Suppress("UNCHECKED_CAST")
            val signals = output["signals"] as? List<*>
            assertNotNull(signals)
            assertTrue(signals.isNotEmpty(), "Should have at least 1 signal")
        }
    }

    private fun assertFalse(condition: Boolean, message: String) {
        kotlin.test.assertFalse(condition, message)
    }
}
