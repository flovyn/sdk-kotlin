package ai.flovyn.sdk.e2e

import ai.flovyn.sdk.e2e.fixtures.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Comprehensive E2E tests that exercise multiple SDK features in single workflow executions.
 *
 * These tests require Docker and a running Flovyn server stack.
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComprehensiveE2ETest {

    private lateinit var env: E2ETestEnvironment
    private lateinit var testPrefix: String

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        val builder = E2ETestEnvironment.builder("ComprehensiveE2ETest")
        testPrefix = builder.testPrefix

        env = builder
            .registerWorkflow(ComprehensiveWorkflow(testPrefix))
            .registerWorkflow(DoublerWorkflow())
            .registerWorkflow(EchoWorkflow(testPrefix))
            .registerWorkflow(StatefulWorkflow(testPrefix))
            .registerWorkflow(FailingWorkflow(testPrefix))
            .registerWorkflow(RandomWorkflow(testPrefix))
            .registerWorkflow(SleepWorkflow(testPrefix))
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Single comprehensive test that validates:
     * - Basic workflow execution
     * - Input/output handling
     * - Operation recording (ctx.run)
     * - State set/get operations
     * - Multiple operations in sequence
     */
    @Test
    fun `test comprehensive workflow features`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("comprehensive-workflow"),
                input = ComprehensiveInput(value = 21),
                timeout = 30.seconds
            )

            // Verify workflow completed
            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete successfully")

            // Verify output
            assertNotNull(result.output, "Output should not be null")

            // Test 1: Basic input is preserved
            assertEquals(21, result.output!!["inputValue"], "Input value should be preserved")

            // Test 2: ctx.run result (21 * 2 = 42)
            assertEquals(42, result.output!!["runResult"], "ctx.run should double the value")

            // Test 3: State was set
            assertEquals(true, result.output!!["stateSet"], "State should be set")

            // Test 4: State was retrieved correctly
            assertEquals(true, result.output!!["stateMatches"], "Retrieved state should match set state")

            // Test 5: Multiple operations (21 * 3 = 63)
            assertEquals(63, result.output!!["tripleResult"], "Triple result should be correct")

            // Test 6: All tests passed
            assertEquals(5, result.output!!["testsPassedCount"], "All 5 tests should pass")

            // Verify specific state content (matching Python SDK assertions)
            @Suppress("UNCHECKED_CAST")
            val stateRetrieved = result.output!!["stateRetrieved"] as? Map<String, Any?>
            assertNotNull(stateRetrieved, "State retrieved should not be null")
            assertEquals(21, stateRetrieved["counter"], "State counter should equal input value")
            assertEquals("state test", stateRetrieved["message"], "State message should match")

            @Suppress("UNCHECKED_CAST")
            val nested = stateRetrieved["nested"] as? Map<String, Any?>
            assertNotNull(nested, "Nested state should be present")
            assertEquals(1, nested["a"], "Nested a should be 1")
            assertEquals(2, nested["b"], "Nested b should be 2")
        }
    }

    /**
     * Test doubler workflow with verified output.
     */
    @Test
    fun `test doubler workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = "doubler-workflow",
                input = DoublerInput(value = 21),
                timeout = 10.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(42, result.output!!["result"], "Result should be doubled")
        }
    }

    /**
     * Test echo workflow with verified output.
     */
    @Test
    fun `test echo workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val testMessage = "Hello from comprehensive test"
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("echo-workflow"),
                input = EchoInput(message = testMessage),
                timeout = 10.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals(testMessage, result.output!!["message"], "Message should be echoed")
            assertNotNull(result.output!!["timestamp"], "Timestamp should be present")
        }
    }

    /**
     * Test stateful workflow with verified state operations.
     */
    @Test
    fun `test stateful workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("stateful-workflow"),
                input = StatefulInput(key = "comprehensive-key", value = "comprehensive-value"),
                timeout = 10.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")
            assertEquals("comprehensive-value", result.output!!["retrievedValue"], "Retrieved value should match")

            @Suppress("UNCHECKED_CAST")
            val allKeys = result.output!!["allKeys"] as? List<String>
            assertNotNull(allKeys, "allKeys should be present")
            assertTrue(allKeys.contains("comprehensive-key"), "allKeys should contain the set key")
        }
    }

    /**
     * Test failing workflow properly reports failure.
     */
    @Test
    fun `test failing workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("failing-workflow"),
                input = FailingInput(shouldFail = true, message = "Expected failure in comprehensive test"),
                timeout = 10.seconds
            )

            // Workflow should fail
            assertEquals(WorkflowStatus.FAILED, result.status, "Workflow should fail")
            assertNotNull(result.error, "Error should be present")
            assertTrue(
                result.error!!.contains("Expected failure", ignoreCase = true),
                "Error should contain the failure message"
            )
        }
    }

    /**
     * Test non-failing workflow completes successfully.
     */
    @Test
    fun `test non-failing workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("failing-workflow"),
                input = FailingInput(shouldFail = false),
                timeout = 10.seconds
            )

            // Workflow should complete (not fail since shouldFail = false)
            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete when shouldFail=false")
        }
    }

    /**
     * Test comprehensive workflow with different input value.
     * Validates the same features with a different input to ensure determinism.
     * (Matching Python SDK test_comprehensive_with_different_input)
     */
    @Test
    fun `test comprehensive with different input`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("comprehensive-workflow"),
                input = ComprehensiveInput(value = 50),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Validate all features with input value 50
            assertEquals(50, result.output!!["inputValue"], "Input value should be 50")
            assertEquals(100, result.output!!["runResult"], "ctx.run should double value (50*2=100)")
            assertEquals(true, result.output!!["stateSet"], "State should be set")
            assertEquals(true, result.output!!["stateMatches"], "State get should return what was set")
            assertEquals(150, result.output!!["tripleResult"], "Triple operation should work (50*3=150)")
            assertEquals(5, result.output!!["testsPassedCount"], "All 5 tests should pass")

            // Verify state content with value 50
            @Suppress("UNCHECKED_CAST")
            val stateRetrieved = result.output!!["stateRetrieved"] as? Map<String, Any?>
            assertNotNull(stateRetrieved, "State retrieved should not be null")
            assertEquals(50, stateRetrieved["counter"], "State counter should equal input value")
        }
    }

    /**
     * Test random workflow generates valid random values.
     * (Matching Python SDK test_all_basic_workflows random check)
     */
    @Test
    fun `test random workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("random-workflow"),
                input = RandomInput(count = 1),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify UUID is generated
            @Suppress("UNCHECKED_CAST")
            val uuids = result.output!!["uuids"] as? List<String>
            assertNotNull(uuids, "UUIDs should be present")
            assertTrue(uuids.isNotEmpty(), "At least one UUID should be generated")
            assertTrue(uuids[0].isNotBlank(), "UUID should not be blank")

            // Verify random float is in range [0, 1)
            @Suppress("UNCHECKED_CAST")
            val randomDoubles = result.output!!["randomDoubles"] as? List<Double>
            assertNotNull(randomDoubles, "Random doubles should be present")
            assertTrue(randomDoubles.isNotEmpty(), "At least one random double should be generated")
            val randomFloat = randomDoubles[0]
            assertTrue(randomFloat >= 0.0, "Random float should be >= 0")
            assertTrue(randomFloat < 1.0, "Random float should be < 1.0")
        }
    }

    /**
     * Test sleep workflow with verified timing.
     * (Matching Python SDK test_all_basic_workflows sleep check)
     */
    @Test
    fun `test sleep workflow`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val durationMs = 50L
            val result = env.startAndAwait(
                workflowKind = env.workflowKind("sleep-workflow"),
                input = SleepInput(durationMs = durationMs),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Verify sleep duration matches input (Python SDK pattern)
            val sleptMs = (result.output!!["sleptMs"] as? Number)?.toLong()
            assertNotNull(sleptMs, "Slept duration should be present")
            assertEquals(durationMs, sleptMs, "Slept duration should match input")
        }
    }
}
