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
 * E2E tests for worker lifecycle functionality.
 *
 * Tests verifying:
 * - Worker registration
 * - Worker status APIs
 * - Worker uptime tracking
 * - Configuration accessors
 * - Worker resilience
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleE2ETest {

    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(EchoWorkflow())
            .registerWorkflow(DoublerWorkflow())
            .registerWorkflow(FailingWorkflow())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test that worker registers successfully with the server.
     *
     * Verifies:
     * - Worker started without errors
     * - Worker is in running state
     */
    @Test
    fun `test worker registration`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // If we get here, registration succeeded
            assertTrue(env.isStarted, "Worker should have started")
        }
    }

    /**
     * Test that worker can process multiple workflows.
     *
     * Verifies:
     * - Multiple workflows can be started concurrently
     * - All workflows complete with correct results
     */
    @Test
    fun `test worker processes multiple workflows`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Start multiple workflows
            val handles = (1..3).map { i ->
                env.startWorkflow("doubler-workflow", DoublerInput(value = i))
            }

            // All should complete successfully with correct results
            handles.forEachIndexed { index, executionId ->
                val result = env.awaitCompletion(executionId, timeout = 30.seconds)
                assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow $index should complete")
                assertNotNull(result.output, "Workflow $index should have output")
                assertEquals((index + 1) * 2, result.output["result"], "Result should be doubled: ${(index + 1) * 2}")
            }
        }
    }

    /**
     * Test that worker status is 'running' after start.
     *
     * Verifies:
     * - Worker status API is accessible
     * - Status shows 'running' after successful start
     */
    @Test
    fun `test worker status running`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val status = env.workerStatus
            assertEquals("running", status, "Worker status should be 'running', got '$status'")
        }
    }

    /**
     * Test that worker stays running after processing a workflow.
     *
     * Verifies:
     * - Worker doesn't exit after completing work
     * - Status remains 'running' after workflow completion
     */
    @Test
    fun `test worker continues after workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Verify running before
            assertEquals("running", env.workerStatus, "Worker should be running initially")

            // Process a workflow
            val result = env.startAndAwait(
                workflowKind = "echo-workflow",
                input = EchoInput(message = "lifecycle test"),
                timeout = 30.seconds,
            )
            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Workflow should have output")
            assertEquals("lifecycle test", result.output["message"], "Message should be echoed")

            // Verify still running after
            assertEquals("running", env.workerStatus, "Worker should still be running after workflow")
        }
    }

    /**
     * Test that worker continues running after a workflow failure.
     *
     * Verifies:
     * - Worker is resilient to individual workflow failures
     * - Worker can process more workflows after a failure
     */
    @Test
    fun `test worker handles workflow errors`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Start a failing workflow
            val failResult = env.startAndAwait(
                workflowKind = "failing-workflow",
                input = FailingInput(shouldFail = true, message = "Expected failure"),
                timeout = 30.seconds,
            )
            assertEquals(WorkflowStatus.FAILED, failResult.status, "Workflow should fail")

            // Worker should still be running
            assertEquals("running", env.workerStatus, "Worker should still be running after failure")

            // Should be able to process more workflows
            val successResult = env.startAndAwait(
                workflowKind = "echo-workflow",
                input = EchoInput(message = "after-failure"),
                timeout = 30.seconds,
            )
            assertEquals(
                WorkflowStatus.COMPLETED,
                successResult.status,
                "Workflow should complete after previous failure",
            )
            assertNotNull(successResult.output, "Success workflow should have output")
            assertEquals("after-failure", successResult.output["message"], "Message should be echoed")
        }
    }

    /**
     * Test that worker uptime API works correctly.
     *
     * Verifies:
     * - Uptime is available after worker starts
     * - Uptime increases over time
     */
    @Test
    fun `test worker uptime`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Get initial uptime
            val uptime1 = env.workerUptimeMs
            assertTrue(uptime1 >= 0, "Uptime should be non-negative, got $uptime1")

            // Wait a bit
            delay(100)

            // Get uptime again
            val uptime2 = env.workerUptimeMs
            assertTrue(uptime2 > uptime1, "Uptime should increase: $uptime2 > $uptime1")
        }
    }

    /**
     * Test that worker start time is recorded correctly.
     *
     * Verifies:
     * - Start time is available
     * - Start time is a reasonable timestamp
     */
    @Test
    fun `test worker started at`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val startedAt = env.workerStartedAtMs
            assertTrue(startedAt > 0, "Started at should be positive: $startedAt")

            // Check that start time is in the past
            val nowMs = System.currentTimeMillis()
            assertTrue(startedAt <= nowMs, "Started at should be in the past: $startedAt <= $nowMs")

            // Should have started within the last hour (reasonable for tests)
            val oneHourAgo = nowMs - (60 * 60 * 1000)
            assertTrue(startedAt > oneHourAgo, "Started at should be recent: $startedAt > $oneHourAgo")
        }
    }

    /**
     * Test that worker ID is assigned after registration.
     *
     * Verifies:
     * - Worker ID is available after start
     * - Worker ID is non-empty
     */
    @Test
    fun `test worker id assigned`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val workerId = env.workerId
            assertNotNull(workerId, "Worker ID should be available")
            assertTrue(workerId.isNotBlank(), "Worker ID should be non-empty")
        }
    }

    /**
     * Test configuration accessor methods.
     *
     * Verifies:
     * - Max concurrent settings are accessible
     * - Values are positive integers
     */
    @Test
    fun `test client config accessors`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Verify configuration accessors
            val maxWorkflows = env.maxConcurrentWorkflows
            val maxTasks = env.maxConcurrentTasks

            assertTrue(maxWorkflows > 0, "Max concurrent workflows should be positive")
            assertTrue(maxTasks > 0, "Max concurrent tasks should be positive")

            // Check default values (10 workflows, 20 tasks)
            assertEquals(10, maxWorkflows, "Default max workflows should be 10, got $maxWorkflows")
            assertEquals(20, maxTasks, "Default max tasks should be 20, got $maxTasks")
        }
    }

    /**
     * Test that workflow and task registration info is accessible.
     *
     * Verifies:
     * - Registered workflows can be queried
     * - Registered tasks can be queried
     */
    @Test
    fun `test registration info accessible`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Check registered workflows
            assertTrue(env.hasWorkflow("echo-workflow"), "Should have echo-workflow registered")
            assertTrue(env.hasWorkflow("doubler-workflow"), "Should have doubler-workflow registered")
            assertTrue(env.hasWorkflow("failing-workflow"), "Should have failing-workflow registered")

            // Check unregistered workflow
            assertTrue(!env.hasWorkflow("non-existent-workflow"), "Should not have non-existent workflow")
        }
    }

    /**
     * Test that isRunning property works correctly.
     *
     * Verifies:
     * - isRunning returns true when worker is started
     */
    @Test
    fun `test is running property`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            assertTrue(env.isRunning, "Worker should be running")
        }
    }
}
