package ai.flovyn.sdk.e2e.fixtures

import ai.flovyn.sdk.common.SemanticVersion
import ai.flovyn.sdk.workflow.WorkflowContext
import ai.flovyn.sdk.workflow.WorkflowDefinition
import ai.flovyn.sdk.workflow.schedule
import java.time.Duration
import java.util.UUID

/**
 * Test workflow fixtures for E2E tests.
 * Mirrors the Rust SDK test workflow patterns.
 */

// --- Data Classes ---

data class EchoInput(val message: String)
data class EchoOutput(val message: String, val timestamp: Long)

data class DoublerInput(val value: Int)
data class DoublerOutput(val result: Int)

data class FailingInput(val shouldFail: Boolean, val message: String = "Test failure")

data class StatefulInput(val key: String, val value: String)
data class StatefulOutput(val retrievedValue: String?, val allKeys: List<String>)

data class TaskSchedulingInput(val numbers: List<Int>)
data class TaskSchedulingOutput(val sum: Int)

// --- Workflows ---

/**
 * Simple echo workflow - returns input unchanged.
 */
class EchoWorkflow : WorkflowDefinition<EchoInput, EchoOutput>() {
    override val kind = "echo-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: EchoInput): EchoOutput {
        return EchoOutput(
            message = input.message,
            timestamp = ctx.currentTimeMillis()
        )
    }
}

/**
 * Doubler workflow - doubles the input value.
 */
class DoublerWorkflow : WorkflowDefinition<DoublerInput, DoublerOutput>() {
    override val kind = "doubler-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: DoublerInput): DoublerOutput {
        return DoublerOutput(result = input.value * 2)
    }
}

/**
 * Failing workflow - throws an error if shouldFail is true.
 */
class FailingWorkflow : WorkflowDefinition<FailingInput, Unit>() {
    override val kind = "failing-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: FailingInput) {
        if (input.shouldFail) {
            throw RuntimeException(input.message)
        }
    }
}

/**
 * Stateful workflow - demonstrates state management.
 */
class StatefulWorkflow : WorkflowDefinition<StatefulInput, StatefulOutput>() {
    override val kind = "stateful-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: StatefulInput): StatefulOutput {
        // Set state
        ctx.set(input.key, input.value)

        // Get state back
        val retrieved: String? = ctx.get(input.key)

        // Get all keys
        val keys = ctx.stateKeys()

        return StatefulOutput(
            retrievedValue = retrieved,
            allKeys = keys
        )
    }
}

/**
 * Task scheduling workflow - schedules tasks and aggregates results.
 */
class TaskSchedulingWorkflow : WorkflowDefinition<TaskSchedulingInput, TaskSchedulingOutput>() {
    override val kind = "task-scheduling-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: TaskSchedulingInput): TaskSchedulingOutput {
        var sum = 0
        for (num in input.numbers) {
            val result = ctx.schedule<AddTaskOutput>(
                taskType = "add-task",
                input = AddTaskInput(a = sum, b = num)
            )
            sum = result.result
        }
        return TaskSchedulingOutput(sum = sum)
    }
}

/**
 * Run operation workflow - demonstrates ctx.run().
 */
class RunOperationWorkflow : WorkflowDefinition<EchoInput, EchoOutput>() {
    override val kind = "run-operation-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: EchoInput): EchoOutput {
        // Use run to record an operation
        val uppercased = ctx.run("uppercase") {
            input.message.uppercase()
        }

        return EchoOutput(
            message = uppercased,
            timestamp = ctx.currentTimeMillis()
        )
    }
}

// --- Random/Sleep/Promise Data Classes ---

data class RandomInput(val count: Int)
data class RandomOutput(
    val uuids: List<String>,
    val randomInts: List<Int>,
    val randomDoubles: List<Double>
)

data class SleepInput(val durationMs: Long)
data class SleepOutput(val startTime: Long, val endTime: Long, val sleptMs: Long)

data class PromiseInput(val promiseName: String)
data class PromiseOutput(val promiseName: String, val created: Boolean)

data class AwaitPromiseInput(val promiseName: String)
data class AwaitPromiseOutput(val promiseName: String, val resolvedValue: String)

// --- Random/Sleep/Promise Workflows ---

/**
 * Random workflow - tests deterministic random number generation.
 * Generates UUIDs and random numbers that should be consistent on replay.
 */
class RandomWorkflow : WorkflowDefinition<RandomInput, RandomOutput>() {
    override val kind = "random-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: RandomInput): RandomOutput {
        val uuids = mutableListOf<String>()
        val randomInts = mutableListOf<Int>()
        val randomDoubles = mutableListOf<Double>()

        val random = ctx.random()

        for (i in 0 until input.count) {
            uuids.add(ctx.randomUUID().toString())
            randomInts.add(random.nextInt(1000))
            randomDoubles.add(random.nextDouble())
        }

        return RandomOutput(
            uuids = uuids,
            randomInts = randomInts,
            randomDoubles = randomDoubles
        )
    }
}

/**
 * Sleep workflow - tests durable timers.
 * Sleeps for a specified duration and returns timing information.
 */
class SleepWorkflow : WorkflowDefinition<SleepInput, SleepOutput>() {
    override val kind = "sleep-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: SleepInput): SleepOutput {
        val startTime = ctx.currentTimeMillis()

        // Sleep for the specified duration
        ctx.sleep(Duration.ofMillis(input.durationMs))

        val endTime = ctx.currentTimeMillis()

        return SleepOutput(
            startTime = startTime,
            endTime = endTime,
            sleptMs = endTime - startTime
        )
    }
}

/**
 * Promise workflow - tests durable promise creation.
 * Creates a promise and returns information about it.
 */
class PromiseWorkflow : WorkflowDefinition<PromiseInput, PromiseOutput>() {
    override val kind = "promise-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: PromiseInput): PromiseOutput {
        // Create a durable promise
        val promise = ctx.promise<String>(input.promiseName, Duration.ofSeconds(30))

        return PromiseOutput(
            promiseName = input.promiseName,
            created = true
        )
    }
}

/**
 * Await promise workflow - creates a promise and waits for it to be resolved.
 * Used for testing external promise resolution.
 */
class AwaitPromiseWorkflow : WorkflowDefinition<AwaitPromiseInput, AwaitPromiseOutput>() {
    override val kind = "await-promise-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: AwaitPromiseInput): AwaitPromiseOutput {
        // Create a durable promise and wait for it
        val promise = ctx.promise<String>(input.promiseName, Duration.ofSeconds(60))
        val resolvedValue = promise.await()

        return AwaitPromiseOutput(
            promiseName = input.promiseName,
            resolvedValue = resolvedValue
        )
    }
}
