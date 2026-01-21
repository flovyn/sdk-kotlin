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
 * E2E tests for durable timer functionality.
 *
 * Tests verifying:
 * - Short timers (milliseconds)
 * - Longer timers (seconds)
 * - Timer duration accuracy
 *
 * Run with: ./gradlew :worker-sdk-jackson:e2eTest
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimerE2ETest {

    private val logger = LoggerFactory.getLogger(TimerE2ETest::class.java)
    private lateinit var env: E2ETestEnvironment

    @BeforeAll
    fun setUp(): Unit = runBlocking {
        env = E2ETestEnvironment.builder()
            .registerWorkflow(ShortTimerWorkflow())
            .registerWorkflow(SleepWorkflow())
            .buildAndStart()
    }

    @AfterAll
    fun tearDown() {
        env.stop()
    }

    /**
     * Test short timer with minimal duration.
     *
     * Verifies:
     * - Timer fires for very short durations (10ms)
     * - Workflow completes after timer
     */
    @Test
    fun `test short timer`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val timerDuration = 10L
            val result = env.startAndAwait(
                workflowKind = "short-timer-workflow",
                input = ShortTimerInput(durationMs = timerDuration),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            assertEquals(true, result.output!!["timerFired"], "Timer should fire")

            // Check that elapsedMs matches input duration (Python SDK pattern)
            val elapsedMs = (result.output!!["elapsedMs"] as Number).toLong()
            assertEquals(
                timerDuration, elapsedMs,
                "Elapsed time should match input duration"
            )

            logger.debug("Short timer completed with duration {} ms", elapsedMs)
        }
    }

    /**
     * Test durable timer sleep with longer duration.
     *
     * Verifies:
     * - Sleep workflow properly suspends and resumes
     * - Duration is recorded accurately
     */
    @Test
    fun `test durable timer sleep`(): Unit = runBlocking {
        withTimeout(E2ETestEnvironment.TEST_TIMEOUT) {
            val sleepDuration = 200L
            val result = env.startAndAwait(
                workflowKind = "sleep-workflow",
                input = SleepInput(durationMs = sleepDuration),
                timeout = 30.seconds
            )

            assertEquals(WorkflowStatus.COMPLETED, result.status, "Workflow should complete")
            assertNotNull(result.output, "Output should not be null")

            // Check that sleptMs matches input duration (Python SDK pattern)
            val sleptMs = (result.output!!["sleptMs"] as Number).toLong()
            assertEquals(
                sleepDuration, sleptMs,
                "Slept time should match input duration"
            )

            // Verify timing fields are present
            val startTime = result.output!!["startTime"] as Number
            val endTime = result.output!!["endTime"] as Number
            assertNotNull(startTime, "Start time should be recorded")
            assertNotNull(endTime, "End time should be recorded")

            logger.debug("Durable timer sleep completed with duration {} ms", sleptMs)
        }
    }

    /**
     * Test multiple sequential timers.
     */
    @Test
    fun `test sequential timers`(): Unit = runBlocking {
        withTimeout(90.seconds) {
            // First timer
            val result1 = env.startAndAwait(
                workflowKind = "short-timer-workflow",
                input = ShortTimerInput(durationMs = 50),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result1.status, "First timer should complete")

            // Second timer
            val result2 = env.startAndAwait(
                workflowKind = "short-timer-workflow",
                input = ShortTimerInput(durationMs = 100),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result2.status, "Second timer should complete")

            // Third timer
            val result3 = env.startAndAwait(
                workflowKind = "short-timer-workflow",
                input = ShortTimerInput(durationMs = 75),
                timeout = 30.seconds
            )
            assertEquals(WorkflowStatus.COMPLETED, result3.status, "Third timer should complete")

            logger.debug("All sequential timers completed successfully")
        }
    }
}
