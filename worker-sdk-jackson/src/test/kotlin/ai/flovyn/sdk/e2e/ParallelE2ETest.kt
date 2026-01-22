package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * E2E tests for parallel task execution (fan-out/fan-in patterns).
 * Matches Python SDK's test_parallel.py assertions.
 *
 * Tests verifying:
 * - Parallel task scheduling with scheduleAsync
 * - Fan-out/fan-in aggregation with string items
 * - Large batch processing with sum calculation
 * - Mixed parallel operations with phases
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParallelE2ETest {

    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(FanOutFanInWorkflow())
            .registerWorkflow(LargeBatchWorkflow())
            .registerWorkflow(MixedParallelWorkflow())
            .registerTask(AddTask())
            .registerTask(EchoTask())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test fan-out/fan-in pattern - scatter tasks and gather results.
     * Matches Python SDK's test_fan_out_fan_in.
     *
     * Verifies:
     * - input_count == 4
     * - output_count == 4
     * - processed_items == set of input items
     * - total_length == sum of all item lengths
     */
    @Test
    fun `test fan out fan in`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val items = listOf("apple", "banana", "cherry", "date")
            val result = env.startAndAwait(
                workflowKind = "fan-out-fan-in-workflow",
                input = FanOutFanInInput(items = items),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify counts
            assertEquals(4, result.output["inputCount"], "Input count should be 4")
            assertEquals(4, result.output["outputCount"], "Output count should be 4")

            // Verify all items were processed
            @Suppress("UNCHECKED_CAST")
            val processedItems = result.output["processedItems"] as? List<String>
            assertNotNull(processedItems, "Processed items should be present")
            assertEquals(items.toSet(), processedItems.toSet(), "All items should be echoed back")

            // Verify total length: 5 + 6 + 6 + 4 = 21
            val expectedLength = items.sumOf { it.length }
            assertEquals(expectedLength, result.output["totalLength"], "Total length should be $expectedLength")
        }
    }

    /**
     * Test large batch parallel processing.
     * Matches Python SDK's test_parallel_large_batch.
     *
     * Verifies:
     * - task_count == 20
     * - total == 210 (sum 1..20)
     * - min_value == 1
     * - max_value == 20
     */
    @Test
    fun `test parallel large batch`(): Unit = runBlocking {
        withTimeout(90.seconds) {
            val count = 20
            val result = env.startAndAwait(
                workflowKind = "large-batch-workflow",
                input = LargeBatchInput(count = count),
                timeout = 60.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify task count
            assertEquals(count, result.output["taskCount"], "Task count should be $count")

            // Sum of 1..20 = 20 * 21 / 2 = 210
            val expectedTotal = count * (count + 1) / 2
            assertEquals(expectedTotal, result.output["total"], "Total should be $expectedTotal")

            // Verify min and max
            assertEquals(1, result.output["minValue"], "Min value should be 1")
            assertEquals(count, result.output["maxValue"], "Max value should be $count")
        }
    }

    /**
     * Test empty batch edge case.
     * Matches Python SDK's test_parallel_empty_batch.
     */
    @Test
    fun `test parallel empty batch`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "fan-out-fan-in-workflow",
                input = FanOutFanInInput(items = emptyList()),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            assertEquals(0, result.output["inputCount"], "Input count should be 0")
            assertEquals(0, result.output["outputCount"], "Output count should be 0")

            @Suppress("UNCHECKED_CAST")
            val processedItems = result.output["processedItems"] as? List<String>
            assertNotNull(processedItems, "Processed items should be present")
            assertEquals(emptyList<String>(), processedItems, "Processed items should be empty")
            assertEquals(0, result.output["totalLength"], "Total length should be 0")
        }
    }

    /**
     * Test single item batch edge case.
     * Matches Python SDK's test_parallel_single_item.
     */
    @Test
    fun `test parallel single item`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "fan-out-fan-in-workflow",
                input = FanOutFanInInput(items = listOf("only-one")),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            assertEquals(1, result.output["inputCount"], "Input count should be 1")
            assertEquals(1, result.output["outputCount"], "Output count should be 1")

            @Suppress("UNCHECKED_CAST")
            val processedItems = result.output["processedItems"] as? List<String>
            assertNotNull(processedItems, "Processed items should be present")
            assertEquals(listOf("only-one"), processedItems, "Processed items should match")
            assertEquals(8, result.output["totalLength"], "Total length should be 8 (len('only-one'))")
        }
    }

    /**
     * Test parallel tasks join_all pattern.
     * Matches Python SDK's test_parallel_tasks_join_all.
     */
    @Test
    fun `test parallel tasks join all`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val items = listOf("a", "b", "c")
            val result = env.startAndAwait(
                workflowKind = "fan-out-fan-in-workflow",
                input = FanOutFanInInput(items = items),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            assertEquals(3, result.output["inputCount"], "Input count should be 3")
            assertEquals(3, result.output["outputCount"], "Output count should be 3")

            @Suppress("UNCHECKED_CAST")
            val processedItems = result.output["processedItems"] as? List<String>
            assertNotNull(processedItems, "Processed items should be present")
            assertEquals(items.toSet(), processedItems.toSet(), "All items should be processed")
        }
    }

    /**
     * Test mixed parallel operations - three-phase workflow with tasks and timers.
     * Matches Python SDK's test_mixed_parallel_operations.
     *
     * Phase 1: Two parallel echo tasks
     * Timer: 100ms
     * Phase 3: Three parallel add tasks (i + i for i in [0,1,2])
     */
    @Test
    fun `test mixed parallel operations`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "mixed-parallel-workflow",
                input = MixedParallelInput(),
                timeout = 30.seconds,
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify success flag
            assertEquals(true, result.output["success"], "Workflow should succeed")

            // Phase 1: Two echo results
            @Suppress("UNCHECKED_CAST")
            val phase1Results = result.output["phase1Results"] as? List<String>
            assertNotNull(phase1Results, "Phase 1 results should be present")
            assertEquals(2, phase1Results.size, "Phase 1 should have 2 results")

            // Timer fired
            assertEquals(true, result.output["timerFired"], "Timer should fire")

            // Phase 3: [0+0, 1+1, 2+2] = [0, 2, 4]
            @Suppress("UNCHECKED_CAST")
            val phase3Results = result.output["phase3Results"] as? List<Int>
            assertNotNull(phase3Results, "Phase 3 results should be present")
            assertEquals(3, phase3Results.size, "Phase 3 should have 3 results")
            assertEquals(listOf(0, 2, 4), phase3Results, "Phase 3 results should be [0, 2, 4]")
        }
    }
}
