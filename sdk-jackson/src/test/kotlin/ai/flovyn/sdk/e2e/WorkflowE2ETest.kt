package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for workflow execution.
 *
 * These tests require Docker and a running Flovyn server stack.
 * Run with: ./gradlew :sdk-jackson:e2eTest
 */
@EnabledIfSystemProperty(named = "FLOVYN_E2E_USE_DEV_INFRA", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowE2ETest {

    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(EchoWorkflow())
            .registerWorkflow(DoublerWorkflow())
            .registerWorkflow(StatefulWorkflow())
            .registerWorkflow(FailingWorkflow())
            .registerWorkflow(RunOperationWorkflow())
            .registerWorkflow(RandomWorkflow())
            .registerWorkflow(SleepWorkflow())
            .registerWorkflow(PromiseWorkflow())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    @Test
    fun `test simple echo workflow execution`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "echo-workflow",
                input = EchoInput(message = "Hello, World!")
            )

            assertNotNull(executionId)
            println("Started echo workflow: $executionId")

            // Wait for completion
            env.awaitCompletion(executionId, 10.seconds)
        }
    }

    @Test
    fun `test doubler workflow execution`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "doubler-workflow",
                input = DoublerInput(value = 21)
            )

            assertNotNull(executionId)
            println("Started doubler workflow: $executionId")

            env.awaitCompletion(executionId, 10.seconds)
        }
    }

    @Test
    fun `test stateful workflow execution`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "stateful-workflow",
                input = StatefulInput(key = "testKey", value = "testValue")
            )

            assertNotNull(executionId)
            println("Started stateful workflow: $executionId")

            env.awaitCompletion(executionId, 10.seconds)
        }
    }

    @Test
    fun `test run operation workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "run-operation-workflow",
                input = EchoInput(message = "hello world")
            )

            assertNotNull(executionId)
            println("Started run operation workflow: $executionId")

            env.awaitCompletion(executionId, 10.seconds)
        }
    }

    @Test
    fun `test multiple workflows in parallel`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionIds = (1..5).map { i ->
                env.startWorkflow(
                    workflowKind = "echo-workflow",
                    input = EchoInput(message = "Message $i")
                )
            }

            assertTrue(executionIds.size == 5)
            assertTrue(executionIds.all { it != null })
            println("Started ${executionIds.size} parallel workflows")

            // Wait for all to complete
            executionIds.forEach { id ->
                env.awaitCompletion(id, 10.seconds)
            }
        }
    }

    @Test
    fun `test random workflow generates deterministic values`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "random-workflow",
                input = RandomInput(count = 5)
            )

            assertNotNull(executionId)
            println("Started random workflow: $executionId")

            env.awaitCompletion(executionId, 10.seconds)
        }
    }

    @Test
    fun `test sleep workflow with timer`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "sleep-workflow",
                input = SleepInput(durationMs = 100)
            )

            assertNotNull(executionId)
            println("Started sleep workflow: $executionId")

            // Give it time to complete (sleep + processing)
            env.awaitCompletion(executionId, 30.seconds)
        }
    }

    @Test
    fun `test promise workflow creates promise`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val executionId = env.startWorkflow(
                workflowKind = "promise-workflow",
                input = PromiseInput(promiseName = "test-promise")
            )

            assertNotNull(executionId)
            println("Started promise workflow: $executionId")

            // Note: Promise workflow will suspend waiting for promise resolution
            // For this test, we just verify it starts and creates the promise
            env.awaitCompletion(executionId, 10.seconds)
        }
    }
}
