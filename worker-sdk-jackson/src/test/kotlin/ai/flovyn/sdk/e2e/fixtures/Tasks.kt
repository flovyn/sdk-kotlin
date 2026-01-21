package ai.flovyn.sdk.e2e.fixtures

import ai.flovyn.sdk.common.SemanticVersion
import ai.flovyn.sdk.task.RetryConfig
import ai.flovyn.sdk.task.StreamEvent
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

// --- Streaming Task Fixtures ---

data class StreamingTokenInput(val tokens: List<String>)
data class StreamingTokenOutput(val tokenCount: Int)

data class StreamingProgressInput(val steps: Int)
data class StreamingProgressOutput(val finalProgress: Double)

data class StreamingDataInput(val items: List<Map<String, Any?>>)
data class StreamingDataOutput(val itemsStreamed: Int)

data class StreamingErrorInput(val errorMessage: String, val errorCode: String = "ERR_001")
data class StreamingErrorOutput(val errorSent: Boolean)

data class StreamingAllTypesInput(
    val token: String,
    val progress: Double,
    val data: Map<String, Any?>,
    val errorMessage: String
)
data class StreamingAllTypesOutput(val allTypesSent: Boolean)

/**
 * Streaming token task - streams tokens to client.
 */
class StreamingTokenTask : TaskDefinition<StreamingTokenInput, StreamingTokenOutput>() {
    override val kind = "streaming-token-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: StreamingTokenInput, context: TaskContext): StreamingTokenOutput {
        context.log("info", "Streaming ${input.tokens.size} tokens")

        for (token in input.tokens) {
            context.stream(StreamEvent.Token(token))
        }

        return StreamingTokenOutput(tokenCount = input.tokens.size)
    }
}

/**
 * Streaming progress task - streams progress updates.
 */
class StreamingProgressTask : TaskDefinition<StreamingProgressInput, StreamingProgressOutput>() {
    override val kind = "streaming-progress-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: StreamingProgressInput, context: TaskContext): StreamingProgressOutput {
        context.log("info", "Streaming ${input.steps} progress updates")

        for (i in 1..input.steps) {
            val progress = i.toDouble() / input.steps
            context.stream(StreamEvent.Progress(progress, "Step $i of ${input.steps}"))
            delay(10) // Small delay between updates
        }

        return StreamingProgressOutput(finalProgress = 1.0)
    }
}

/**
 * Streaming data task - streams arbitrary data.
 */
class StreamingDataTask : TaskDefinition<StreamingDataInput, StreamingDataOutput>() {
    override val kind = "streaming-data-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: StreamingDataInput, context: TaskContext): StreamingDataOutput {
        context.log("info", "Streaming ${input.items.size} data items")

        for (item in input.items) {
            // Convert Map to JSON string
            val json = item.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":${if (v is String) "\"$v\"" else v}"
            }
            context.stream(StreamEvent.Data(json))
        }

        return StreamingDataOutput(itemsStreamed = input.items.size)
    }
}

/**
 * Streaming error task - streams error notifications.
 */
class StreamingErrorTask : TaskDefinition<StreamingErrorInput, StreamingErrorOutput>() {
    override val kind = "streaming-error-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: StreamingErrorInput, context: TaskContext): StreamingErrorOutput {
        context.log("info", "Streaming error notification: ${input.errorMessage}")

        context.stream(StreamEvent.Error(input.errorMessage, input.errorCode))

        return StreamingErrorOutput(errorSent = true)
    }
}

/**
 * Streaming all types task - streams all event types.
 */
class StreamingAllTypesTask : TaskDefinition<StreamingAllTypesInput, StreamingAllTypesOutput>() {
    override val kind = "streaming-all-types-task"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(input: StreamingAllTypesInput, context: TaskContext): StreamingAllTypesOutput {
        context.log("info", "Streaming all event types")

        // Stream token
        context.stream(StreamEvent.Token(input.token))

        // Stream progress
        context.stream(StreamEvent.Progress(input.progress, "Progress update"))

        // Stream data
        val json = input.data.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${if (v is String) "\"$v\"" else v}"
        }
        context.stream(StreamEvent.Data(json))

        // Stream error
        context.stream(StreamEvent.Error(input.errorMessage))

        return StreamingAllTypesOutput(allTypesSent = true)
    }
}
