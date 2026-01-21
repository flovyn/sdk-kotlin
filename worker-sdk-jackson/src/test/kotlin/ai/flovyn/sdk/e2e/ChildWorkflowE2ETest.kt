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
 * E2E tests for child workflow execution.
 *
 * These tests require Docker and a running Flovyn server stack.
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChildWorkflowE2ETest {

    private val logger = LoggerFactory.getLogger(ChildWorkflowE2ETest::class.java)
    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(ChildWorkflow())
            .registerWorkflow(ParentWorkflow())
            .registerWorkflow(FailingChildWorkflow())
            .registerWorkflow(ParentWithFailingChildWorkflow())
            .registerWorkflow(NestedChildWorkflow())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test basic child workflow: parent schedules child, receives result.
     *
     * Flow:
     * 1. Start parent workflow that calls ctx.scheduleWorkflow()
     * 2. Child workflow executes and completes
     * 3. Parent receives child's result and completes
     */
    @Test
    fun `test child workflow success`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "parent-workflow",
                input = ParentInput(value = 21),
                timeout = 30.seconds
            )

            // Log result for debugging in CI
            logger.info("Parent workflow result: status={}, output={}, error={}", result.status, result.output, result.error)

            // Verify workflow completed
            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete successfully. Got: ${result.status}, error: ${result.error}")

            // Verify output
            assertNotNull(result.output, "Output should not be null")
            assertEquals(21, result.output!!["originalValue"], "Original value should be preserved. Got: ${result.output}")
            // Child doubles the value: 21 * 2 = 42
            assertEquals(42, result.output!!["childResult"], "Child should double the value. Got: ${result.output}")
        }
    }

    /**
     * Test child workflow failure: child fails, parent receives error.
     *
     * Flow:
     * 1. Start parent workflow that schedules a failing child
     * 2. Child workflow fails with an error
     * 3. Parent handles the error gracefully
     */
    @Test
    fun `test child workflow failure`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "parent-with-failing-child-workflow",
                input = ParentWithFailingChildInput(message = "Intentional child failure"),
                timeout = 30.seconds
            )

            // Parent should complete (handling child error)
            assertEquals(WorkflowStatus.COMPLETED, result.status, "Parent should complete after handling child error")

            // Verify output shows error was caught
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output!!["errorCaught"], "Parent should have caught the child error")

            // Error message should contain the original error
            val errorMessage = result.output!!["errorMessage"] as? String
            assertNotNull(errorMessage, "Error message should be present")
            assertTrue(
                errorMessage.contains("Intentional child failure", ignoreCase = true) ||
                errorMessage.contains("child", ignoreCase = true),
                "Error message should contain relevant information"
            )

            logger.debug("Parent workflow handled child failure: {}", result.output)
        }
    }

    /**
     * Test nested child workflows: multi-level nesting.
     * Matches Python SDK's test_nested_child_workflows.
     *
     * Flow:
     * 1. Start workflow with depth=3 and value="nested"
     * 2. Each level calls a child with depth-1
     * 3. At depth=1, returns "leaf:nested"
     * 4. Result propagates back through all levels
     */
    @Test
    fun `test nested child workflows`(): Unit = runBlocking {
        withTimeout(90.seconds) {
            val result = env.startAndAwait(
                workflowKind = "nested-child-workflow",
                input = NestedInput(depth = 3, value = "nested"),
                timeout = 60.seconds
            )

            // Log result for debugging in CI
            logger.info("Nested workflow result: status={}, output={}, error={}", result.status, result.output, result.error)

            // Verify workflow completed
            assertEquals(WorkflowStatus.COMPLETED, result.status, "Nested workflow should complete successfully. Got: ${result.status}, error: ${result.error}")

            // Verify output
            assertNotNull(result.output, "Output should not be null. status=${result.status}, error=${result.error}")

            // Result should contain "leaf:nested" from the deepest level
            val resultStr = result.output!!["result"] as? String
            assertNotNull(resultStr, "Result string should be present. output=${result.output}")
            assertTrue(resultStr.contains("leaf:nested"), "Result should contain 'leaf:nested'. Got: $resultStr")

            // Verify levels count matches depth
            assertEquals(3, result.output!!["levels"], "Levels should be 3. Got output: ${result.output}")

            logger.debug("Nested child workflows completed with output: {}", result.output)
        }
    }
}
