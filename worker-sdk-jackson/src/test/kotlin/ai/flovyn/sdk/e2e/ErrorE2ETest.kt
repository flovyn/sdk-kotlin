package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for error handling.
 *
 * These tests verify that errors are properly propagated and preserved
 * through the workflow execution pipeline.
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorE2ETest {

    private val logger = LoggerFactory.getLogger(ErrorE2ETest::class.java)
    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(FailingWorkflow())
            .registerWorkflow(ErrorMessageWorkflow())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test that workflow failure is properly reported.
     */
    @Test
    fun `test workflow failure`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "failing-workflow",
                input = FailingInput(shouldFail = true, message = "Test failure message"),
                timeout = 30.seconds
            )

            // Verify workflow failed
            assertEquals(WorkflowStatus.FAILED, result.status, "Workflow should fail")
            assertNotNull(result.error, "Error should be present")

            logger.debug("Workflow failed with error: {}", result.error)
        }
    }

    /**
     * Test that error messages are preserved through workflow failure.
     *
     * Verifies:
     * 1. Workflow fails with RuntimeException
     * 2. Error message is preserved and accessible
     * 3. Original error text is contained in failure details
     */
    @Test
    fun `test error message preserved`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val errorMessage = "Specific error message for preservation test"
            val result = env.startAndAwait(
                workflowKind = "error-message-workflow",
                input = ErrorMessageInput(errorMessage = errorMessage, includeDetails = false),
                timeout = 30.seconds
            )

            // Verify workflow failed
            assertEquals(WorkflowStatus.FAILED, result.status, "Workflow should fail")
            assertNotNull(result.error, "Error should be present")

            // Error message should contain the original error text
            assertTrue(
                result.error!!.contains(errorMessage, ignoreCase = true),
                "Error message should contain the original error text. Got: ${result.error}"
            )

            logger.debug("Error message preserved: {}", result.error)
        }
    }

    /**
     * Test error message with workflow execution details.
     *
     * Verifies that error messages can include contextual information
     * like workflow execution IDs.
     */
    @Test
    fun `test error message with details`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val errorMessage = "Detailed error message"
            val result = env.startAndAwait(
                workflowKind = "error-message-workflow",
                input = ErrorMessageInput(errorMessage = errorMessage, includeDetails = true),
                timeout = 30.seconds
            )

            // Verify workflow failed
            assertEquals(WorkflowStatus.FAILED, result.status, "Workflow should fail")
            assertNotNull(result.error, "Error should be present")

            // Error should contain the base message
            assertTrue(
                result.error!!.contains(errorMessage, ignoreCase = true),
                "Error should contain base message"
            )

            // Error should contain workflow reference (added by the workflow)
            assertTrue(
                result.error!!.contains("workflow", ignoreCase = true),
                "Error should contain workflow reference"
            )

            logger.debug("Detailed error message: {}", result.error)
        }
    }
}
