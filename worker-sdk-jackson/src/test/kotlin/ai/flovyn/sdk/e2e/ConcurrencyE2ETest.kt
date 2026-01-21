package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for concurrent workflow execution.
 *
 * Tests verifying:
 * - Multiple workflows can execute concurrently
 * - Worker handles multiple workflows correctly
 * - Worker resilience after workflow errors
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyE2ETest {

    private val logger = LoggerFactory.getLogger(ConcurrencyE2ETest::class.java)
    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(DoublerWorkflow())
            .registerWorkflow(EchoWorkflow())
            .registerWorkflow(FailingWorkflow())
            .registerWorkflow(SleepWorkflow())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test concurrent workflow execution.
     *
     * Starts multiple workflows simultaneously and verifies all complete correctly.
     */
    @Test
    fun `test concurrent workflow execution`(): Unit = runBlocking {
        withTimeout(120.seconds) {
            val workflowCount = 5

            // Start multiple workflows concurrently
            val jobs = (1..workflowCount).map { i ->
                async {
                    val result = env.startAndAwait(
                        workflowKind = "doubler-workflow",
                        input = DoublerInput(value = i * 10),
                        timeout = 30.seconds
                    )
                    Pair(i, result)
                }
            }

            // Await all results
            val results = jobs.awaitAll()

            // Verify all completed
            assertEquals(workflowCount, results.size, "All workflows should complete")

            for ((i, result) in results) {
                assertEquals(
                    WorkflowStatus.COMPLETED, result.status,
                    "Workflow $i should complete"
                )
                assertNotNull(result.output, "Workflow $i should have output")
                assertEquals(
                    i * 10 * 2, result.output!!["result"],
                    "Workflow $i should double the value"
                )
            }

            logger.debug("All {} concurrent workflows completed successfully", workflowCount)
        }
    }

    /**
     * Test worker continues processing after workflow completion.
     *
     * Verifies worker can process sequential workflows correctly.
     */
    @Test
    fun `test worker continues after workflow`(): Unit = runBlocking {
        withTimeout(90.seconds) {
            // First workflow
            val result1 = env.startAndAwait(
                workflowKind = "doubler-workflow",
                input = DoublerInput(value = 10),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result1.status, "First workflow should complete")
            assertEquals(20, result1.output!!["result"], "First workflow result incorrect")

            // Second workflow - different type
            val result2 = env.startAndAwait(
                workflowKind = "echo-workflow",
                input = EchoInput(message = "After first workflow"),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result2.status, "Second workflow should complete")
            assertEquals("After first workflow", result2.output!!["message"], "Second workflow message incorrect")

            // Third workflow - back to first type
            val result3 = env.startAndAwait(
                workflowKind = "doubler-workflow",
                input = DoublerInput(value = 100),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result3.status, "Third workflow should complete")
            assertEquals(200, result3.output!!["result"], "Third workflow result incorrect")

            logger.debug("Worker successfully processed 3 sequential workflows")
        }
    }

    /**
     * Test worker handles workflow errors and continues processing.
     *
     * Verifies worker resilience: can process workflows after a failure.
     */
    @Test
    fun `test worker handles workflow errors`(): Unit = runBlocking {
        withTimeout(90.seconds) {
            // First: successful workflow
            val result1 = env.startAndAwait(
                workflowKind = "doubler-workflow",
                input = DoublerInput(value = 5),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result1.status, "First workflow should complete")
            assertEquals(10, result1.output!!["result"], "First workflow result incorrect")

            // Second: failing workflow
            val result2 = env.startAndAwait(
                workflowKind = "failing-workflow",
                input = FailingInput(shouldFail = true, message = "Intentional failure"),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.FAILED, result2.status, "Failing workflow should fail")
            assertNotNull(result2.error, "Error should be present")

            // Third: successful workflow after failure
            val result3 = env.startAndAwait(
                workflowKind = "doubler-workflow",
                input = DoublerInput(value = 25),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result3.status, "Workflow after failure should complete")
            assertEquals(50, result3.output!!["result"], "Workflow after failure result incorrect")

            logger.debug("Worker successfully recovered from workflow error")
        }
    }

    /**
     * Test concurrent execution with mixed workflow types.
     *
     * Verifies worker can handle different workflow types simultaneously.
     */
    @Test
    fun `test concurrent mixed workflow types`(): Unit = runBlocking {
        withTimeout(120.seconds) {
            // Start different workflow types concurrently
            val doublerJob = async {
                env.startAndAwait(
                    workflowKind = "doubler-workflow",
                    input = DoublerInput(value = 42),
                    timeout = 30.seconds
                )
            }

            val echoJob = async {
                env.startAndAwait(
                    workflowKind = "echo-workflow",
                    input = EchoInput(message = "Concurrent test"),
                    timeout = 30.seconds
                )
            }

            val sleepJob = async {
                env.startAndAwait(
                    workflowKind = "sleep-workflow",
                    input = SleepInput(durationMs = 100),
                    timeout = 30.seconds
                )
            }

            // Await all
            val doublerResult = doublerJob.await()
            val echoResult = echoJob.await()
            val sleepResult = sleepJob.await()

            // Verify all completed with correct results
            assertEquals(WorkflowStatus.COMPLETED, doublerResult.status, "Doubler should complete")
            assertEquals(84, doublerResult.output!!["result"], "Doubler result incorrect")

            assertEquals(WorkflowStatus.COMPLETED, echoResult.status, "Echo should complete")
            assertEquals("Concurrent test", echoResult.output!!["message"], "Echo message incorrect")

            assertEquals(WorkflowStatus.COMPLETED, sleepResult.status, "Sleep should complete")
            assertNotNull(sleepResult.output!!["sleptMs"], "Sleep duration should be present")
            assertEquals(
                100L,
                (sleepResult.output!!["sleptMs"] as Number).toLong(),
                "Sleep duration should match input"
            )

            logger.debug("Concurrent mixed workflow types completed successfully")
        }
    }
}
