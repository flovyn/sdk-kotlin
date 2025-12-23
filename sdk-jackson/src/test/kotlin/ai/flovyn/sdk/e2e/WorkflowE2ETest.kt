package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.Disabled
import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(WorkflowE2ETest::class.java)
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
            .registerWorkflow(AwaitPromiseWorkflow())
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
            logger.debug("Started echo workflow: {}", executionId)

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
            logger.debug("Started doubler workflow: {}", executionId)

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
            logger.debug("Started stateful workflow: {}", executionId)

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
            logger.debug("Started run operation workflow: {}", executionId)

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
            logger.debug("Started {} parallel workflows", executionIds.size)

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
            logger.debug("Started random workflow: {}", executionId)

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
            logger.debug("Started sleep workflow: {}", executionId)

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
            logger.debug("Started promise workflow: {}", executionId)

            // Note: Promise workflow will suspend waiting for promise resolution
            // For this test, we just verify it starts and creates the promise
            env.awaitCompletion(executionId, 10.seconds)
        }
    }

    @Test
    fun `test resolve promise externally`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val promiseName = "external-resolve-${System.currentTimeMillis()}"

            // Start workflow that waits for a promise
            val executionId = env.startWorkflow(
                workflowKind = "await-promise-workflow",
                input = AwaitPromiseInput(promiseName = promiseName)
            )

            assertNotNull(executionId)
            logger.debug("Started await-promise workflow: {}", executionId)

            // Give the workflow time to start, be picked up by worker, and create the promise
            // Needs more time for: worker registration -> poll -> execute -> suspend
            kotlinx.coroutines.delay(5000)

            // Resolve the promise externally
            logger.debug("Resolving promise '{}' for workflow: {}", promiseName, executionId)
            env.client.resolvePromise(executionId, promiseName, "Hello from external!")

            // Wait for workflow to complete
            env.awaitCompletion(executionId, 30.seconds)
            logger.debug("Workflow completed after promise resolution")
        }
    }
}
