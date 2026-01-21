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
 * E2E tests for replay and determinism validation.
 *
 * Tests verifying:
 * - Mixed command workflows replay correctly
 * - Sequential operations in loops
 * - Operations, timers, and tasks interleaved
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayE2ETest {

    private val logger = LoggerFactory.getLogger(ReplayE2ETest::class.java)
    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(MixedCommandsWorkflow())
            .registerWorkflow(TaskSchedulingWorkflow())
            .registerWorkflow(RandomWorkflow())
            .registerTask(AddTask())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test mixed commands workflow - operations, timers, tasks interleaved.
     *
     * Verifies:
     * - Multiple ctx.run operations execute in sequence
     * - Timer completes
     * - Task executes after timer
     * - All results are correct
     */
    @Test
    fun `test mixed commands workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val initialValue = 10
            val runCount = 3
            val result = env.startAndAwait(
                workflowKind = "mixed-commands-workflow",
                input = MixedCommandsInput(value = initialValue, runCount = runCount),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify initial value
            assertEquals(initialValue, result.output!!["initialValue"], "Initial value should be preserved")

            // Verify run results
            // value=10, run-1: 10+1=11, run-2: 11+2=13, run-3: 13+3=16
            @Suppress("UNCHECKED_CAST")
            val runResults = result.output!!["runResults"] as? List<Int>
            assertNotNull(runResults, "Run results should be present")
            assertEquals(3, runResults.size, "Should have 3 run results")
            assertEquals(listOf(11, 13, 16), runResults, "Run results should be calculated correctly")

            // Verify timer fired
            assertEquals(true, result.output!!["timerFired"], "Timer should fire")

            // Verify task result: 16 + 10 = 26
            assertEquals(26, result.output!!["taskResult"], "Task result should be 26")
            assertEquals(26, result.output!!["finalValue"], "Final value should be 26")

            logger.debug("Mixed commands workflow completed: {}", result.output)
        }
    }

    /**
     * Test sequential tasks in a loop.
     *
     * Verifies:
     * - Tasks scheduled in a loop execute correctly
     * - Running totals accumulate correctly
     */
    @Test
    fun `test sequential tasks in loop`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val numbers = listOf(1, 2, 3, 4, 5)
            val result = env.startAndAwait(
                workflowKind = "task-scheduling-workflow",
                input = TaskSchedulingInput(numbers = numbers),
                timeout = 60.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Sum: 0+1=1, 1+2=3, 3+3=6, 6+4=10, 10+5=15
            assertEquals(15, result.output!!["sum"], "Sum should be 15")

            logger.debug("Sequential task loop completed with sum: {}", result.output!!["sum"])
        }
    }

    /**
     * Test deterministic random number generation.
     *
     * Verifies:
     * - Random UUIDs are generated
     * - Random integers are generated
     * - Random doubles are generated
     */
    @Test
    fun `test deterministic random generation`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val count = 5
            val result = env.startAndAwait(
                workflowKind = "random-workflow",
                input = RandomInput(count = count),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify UUIDs generated
            @Suppress("UNCHECKED_CAST")
            val uuids = result.output!!["uuids"] as? List<String>
            assertNotNull(uuids, "UUIDs should be present")
            assertEquals(count, uuids.size, "Should have $count UUIDs")
            assertTrue(uuids.all { it.isNotBlank() }, "All UUIDs should be non-empty")

            // Verify random integers generated
            @Suppress("UNCHECKED_CAST")
            val randomInts = result.output!!["randomInts"] as? List<Int>
            assertNotNull(randomInts, "Random ints should be present")
            assertEquals(count, randomInts.size, "Should have $count random ints")
            assertTrue(randomInts.all { it in 0..999 }, "All random ints should be in range")

            // Verify random doubles generated
            @Suppress("UNCHECKED_CAST")
            val randomDoubles = result.output!!["randomDoubles"] as? List<Double>
            assertNotNull(randomDoubles, "Random doubles should be present")
            assertEquals(count, randomDoubles.size, "Should have $count random doubles")
            assertTrue(randomDoubles.all { it in 0.0..1.0 }, "All random doubles should be in [0,1)")

            logger.debug("Deterministic random generation completed: {} UUIDs, {} ints, {} doubles",
                uuids.size, randomInts.size, randomDoubles.size)
        }
    }

    /**
     * Test multiple runs of same workflow produce consistent structure.
     *
     * Note: We can't verify identical values without forcing replay,
     * but we verify the structure is consistent.
     */
    @Test
    fun `test workflow consistency`(): Unit = runBlocking {
        withTimeout(90.seconds) {
            // Run same workflow multiple times
            val results = (1..3).map {
                env.startAndAwait(
                    workflowKind = "mixed-commands-workflow",
                    input = MixedCommandsInput(value = 5, runCount = 2),
                    timeout = 30.seconds
                )
            }

            // Verify all completed with same structure
            results.forEach { result ->
                assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
                assertNotNull(result.output, "Output should not be null")
                assertEquals(5, result.output!!["initialValue"], "Initial value should be 5")
                assertEquals(true, result.output!!["timerFired"], "Timer should fire")
                assertNotNull(result.output!!["runResults"], "Run results should be present")
                assertNotNull(result.output!!["taskResult"], "Task result should be present")
            }

            logger.debug("Workflow consistency verified across {} runs", results.size)
        }
    }
}
