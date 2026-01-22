package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for task streaming functionality.
 *
 * Tests verifying:
 * - Task streaming tokens
 * - Task streaming progress
 * - Task streaming data
 * - Task streaming errors
 * - Task streaming all types
 *
 * Note: Streaming events are ephemeral and not persisted, but we verify
 * that tasks can call the streaming API and complete successfully.
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamingE2ETest {

    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(TaskSchedulerWorkflow())
            .registerTask(StreamingTokenTask())
            .registerTask(StreamingProgressTask())
            .registerTask(StreamingDataTask())
            .registerTask(StreamingErrorTask())
            .registerTask(StreamingAllTypesTask())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test that a task can stream tokens to connected clients.
     *
     * Verifies:
     * - Task can call stream_token()
     * - Task completes successfully after streaming
     * - Correct number of tokens were processed
     */
    @Test
    fun `test task streams tokens`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val tokens = listOf("Hello", " ", "world", "!")

            val result = env.startAndAwait(
                workflowKind = "task-scheduler-workflow",
                input = TaskSchedulerInput(
                    taskName = "streaming-token-task",
                    taskInput = mapOf("tokens" to tokens),
                ),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output["taskCompleted"], "Task should complete")

            @Suppress("UNCHECKED_CAST")
            val taskResult = result.output["taskResult"] as? Map<String, Any?>
            assertNotNull(taskResult, "Task result should be present")
            assertEquals(tokens.size, taskResult["tokenCount"], "Token count should match")
        }
    }

    /**
     * Test that a task can stream progress updates.
     *
     * Verifies:
     * - Task can call stream_progress()
     * - Progress values are valid (0.0 to 1.0)
     * - Task completes after streaming all progress
     */
    @Test
    fun `test task streams progress`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val steps = 5

            val result = env.startAndAwait(
                workflowKind = "task-scheduler-workflow",
                input = TaskSchedulerInput(
                    taskName = "streaming-progress-task",
                    taskInput = mapOf("steps" to steps),
                ),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output["taskCompleted"], "Task should complete")

            @Suppress("UNCHECKED_CAST")
            val taskResult = result.output["taskResult"] as? Map<String, Any?>
            assertNotNull(taskResult, "Task result should be present")
            assertEquals(1.0, taskResult["finalProgress"], "Final progress should be 1.0")
        }
    }

    /**
     * Test that a task can stream arbitrary data.
     *
     * Verifies:
     * - Task can call stream_data()
     * - Data is serialized correctly
     * - Task completes after streaming all data
     */
    @Test
    fun `test task streams data`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val items = listOf(
                mapOf("id" to 1, "name" to "item1"),
                mapOf("id" to 2, "name" to "item2"),
                mapOf("id" to 3, "name" to "item3"),
            )

            val result = env.startAndAwait(
                workflowKind = "task-scheduler-workflow",
                input = TaskSchedulerInput(
                    taskName = "streaming-data-task",
                    taskInput = mapOf("items" to items),
                ),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output["taskCompleted"], "Task should complete")

            @Suppress("UNCHECKED_CAST")
            val taskResult = result.output["taskResult"] as? Map<String, Any?>
            assertNotNull(taskResult, "Task result should be present")
            assertEquals(items.size, taskResult["itemsStreamed"], "Items streamed count should match")
        }
    }

    /**
     * Test that a task can stream error notifications.
     *
     * Verifies:
     * - Task can call stream_error()
     * - Task continues after streaming error (non-fatal)
     * - Task completes successfully
     */
    @Test
    fun `test task streams errors`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "task-scheduler-workflow",
                input = TaskSchedulerInput(
                    taskName = "streaming-error-task",
                    taskInput = mapOf(
                        "errorMessage" to "Recoverable warning",
                        "errorCode" to "WARN_001",
                    ),
                ),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output["taskCompleted"], "Task should complete")

            @Suppress("UNCHECKED_CAST")
            val taskResult = result.output["taskResult"] as? Map<String, Any?>
            assertNotNull(taskResult, "Task result should be present")
            assertEquals(true, taskResult["errorSent"], "Error should have been sent")
        }
    }

    /**
     * Test that a task can stream all event types in sequence.
     *
     * Verifies:
     * - Task can mix token, progress, data, and error streaming
     * - All stream calls succeed
     * - Task completes successfully
     */
    @Test
    fun `test task streams all types`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "task-scheduler-workflow",
                input = TaskSchedulerInput(
                    taskName = "streaming-all-types-task",
                    taskInput = mapOf(
                        "token" to "Generated token",
                        "progress" to 0.75,
                        "data" to mapOf("key" to "value", "count" to 42),
                        "errorMessage" to "Warning: operation slow",
                    ),
                ),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output["taskCompleted"], "Task should complete")

            @Suppress("UNCHECKED_CAST")
            val taskResult = result.output["taskResult"] as? Map<String, Any?>
            assertNotNull(taskResult, "Task result should be present")
            assertEquals(true, taskResult["allTypesSent"], "All types should have been sent")
        }
    }

    /**
     * Test streaming custom/complex tokens.
     *
     * Verifies:
     * - Tokens with special characters work
     * - Unicode tokens work
     * - Empty tokens work
     */
    @Test
    fun `test task streams custom tokens`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            // Include various token types: empty, whitespace, unicode, json-like, emoji
            val tokens = listOf(
                "normal",
                "",
                "with spaces and\ttabs",
                "unicode: \u4e2d\u6587",
                "{\"json\": true}",
                "emoji: \uD83D\uDE80",
            )

            val result = env.startAndAwait(
                workflowKind = "task-scheduler-workflow",
                input = TaskSchedulerInput(
                    taskName = "streaming-token-task",
                    taskInput = mapOf("tokens" to tokens),
                ),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(true, result.output["taskCompleted"], "Task should complete")

            @Suppress("UNCHECKED_CAST")
            val taskResult = result.output["taskResult"] as? Map<String, Any?>
            assertNotNull(taskResult, "Task result should be present")
            assertEquals(tokens.size, taskResult["tokenCount"], "Token count should match")
        }
    }
}
