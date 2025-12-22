package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for task execution from workflows.
 *
 * These tests require Docker and a running Flovyn server stack.
 * Run with: ./gradlew :sdk-jackson:e2eTest
 */
@EnabledIfSystemProperty(named = "FLOVYN_E2E_USE_DEV_INFRA", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskE2ETest {

    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(TaskSchedulingWorkflow())
            .registerTask(AddTask())
            .registerTask(EchoTask())
            .registerTask(SlowTask())
            .registerTask(FailingTask())
            .registerTask(ProgressTask())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    @Test
    fun `test workflow scheduling tasks`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "task-scheduling-workflow",
                input = TaskSchedulingInput(numbers = listOf(1, 2, 3, 4, 5))
            )

            assertNotNull(executionId)
            println("Started task scheduling workflow: $executionId")

            // This workflow schedules 5 add tasks, so give it more time
            env.awaitCompletion(executionId, 30.seconds)
        }
    }

    @Test
    fun `test workflow with many tasks`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "task-scheduling-workflow",
                input = TaskSchedulingInput(numbers = (1..10).toList())
            )

            assertNotNull(executionId)
            println("Started workflow with 10 tasks: $executionId")

            env.awaitCompletion(executionId, 60.seconds)
        }
    }
}
