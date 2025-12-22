package ai.flovyn.sdk.e2e.fixtures

import ai.flovyn.sdk.common.SemanticVersion
import ai.flovyn.sdk.task.RetryConfig
import ai.flovyn.sdk.task.TaskContext
import ai.flovyn.sdk.task.TaskDefinition
import kotlinx.coroutines.delay

/**
 * Test task fixtures for E2E tests.
 * Mirrors the Rust SDK test task patterns.
 */

// --- Data Classes ---

data class AddTaskInput(val a: Int, val b: Int)
data class AddTaskOutput(val result: Int)

data class EchoTaskInput(val message: String)
data class EchoTaskOutput(val message: String)

data class SlowTaskInput(val durationMs: Long)
data class SlowTaskOutput(val completed: Boolean)

data class FailingTaskInput(val failCount: Int, val message: String = "Task failure")
data class FailingTaskOutput(val attempt: Int)

// --- Tasks ---

/**
 * Add task - adds two numbers.
 */
class AddTask : TaskDefinition<AddTaskInput, AddTaskOutput>() {
    override val kind = "add-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: AddTaskInput, context: TaskContext): AddTaskOutput {
        context.log("info", "Adding ${input.a} + ${input.b}")
        return AddTaskOutput(result = input.a + input.b)
    }
}

/**
 * Echo task - returns input unchanged.
 */
class EchoTask : TaskDefinition<EchoTaskInput, EchoTaskOutput>() {
    override val kind = "echo-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: EchoTaskInput, context: TaskContext): EchoTaskOutput {
        context.log("info", "Echoing: ${input.message}")
        return EchoTaskOutput(message = input.message)
    }
}

/**
 * Slow task - sleeps for a configurable duration.
 */
class SlowTask : TaskDefinition<SlowTaskInput, SlowTaskOutput>() {
    override val kind = "slow-task"
    override val version = SemanticVersion(1, 0, 0)
    override val timeoutSeconds = 60

    override suspend fun execute(input: SlowTaskInput, context: TaskContext): SlowTaskOutput {
        context.log("info", "Starting slow task, sleeping for ${input.durationMs}ms")
        context.reportProgress(0.0, "Starting")

        delay(input.durationMs)

        context.reportProgress(1.0, "Completed")
        return SlowTaskOutput(completed = true)
    }
}

/**
 * Failing task - fails N times then succeeds.
 * Useful for testing retry logic.
 */
class FailingTask : TaskDefinition<FailingTaskInput, FailingTaskOutput>() {
    override val kind = "failing-task"
    override val version = SemanticVersion(1, 0, 0)
    override val retryConfig = RetryConfig(
        maxAttempts = 5,
        initialDelayMs = 100,
        maxDelayMs = 1000,
        backoffMultiplier = 2.0
    )

    override suspend fun execute(input: FailingTaskInput, context: TaskContext): FailingTaskOutput {
        context.log("info", "Failing task attempt ${context.attempt}, failCount: ${input.failCount}")

        if (context.attempt <= input.failCount) {
            throw RuntimeException("${input.message} (attempt ${context.attempt})")
        }

        return FailingTaskOutput(attempt = context.attempt)
    }
}

/**
 * Progress reporting task - demonstrates progress updates.
 */
class ProgressTask : TaskDefinition<SlowTaskInput, SlowTaskOutput>() {
    override val kind = "progress-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: SlowTaskInput, context: TaskContext): SlowTaskOutput {
        val steps = 5
        val stepDuration = input.durationMs / steps

        for (i in 1..steps) {
            context.reportProgress(i.toDouble() / steps, "Step $i of $steps")
            delay(stepDuration)
        }

        return SlowTaskOutput(completed = true)
    }
}
