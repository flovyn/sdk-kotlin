package ai.flovyn.sdk.e2e.fixtures

import ai.flovyn.sdk.common.SemanticVersion
import ai.flovyn.sdk.workflow.WorkflowContext
import ai.flovyn.sdk.workflow.WorkflowDefinition
import ai.flovyn.sdk.workflow.awaitAll
import ai.flovyn.sdk.workflow.schedule
import ai.flovyn.sdk.workflow.scheduleAsync
import java.time.Duration

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
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class EchoWorkflow(kindSuffix: String = "") : WorkflowDefinition<EchoInput, EchoOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "echo-workflow" else "echo-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: EchoInput): EchoOutput {
        return EchoOutput(
            message = input.message,
            timestamp = ctx.currentTimeMillis(),
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
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class FailingWorkflow(kindSuffix: String = "") : WorkflowDefinition<FailingInput, Unit>() {
    override val kind = if (kindSuffix.isEmpty()) "failing-workflow" else "failing-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: FailingInput) {
        if (input.shouldFail) {
            throw RuntimeException(input.message)
        }
    }
}

/**
 * Stateful workflow - demonstrates state management.
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class StatefulWorkflow(kindSuffix: String = "") : WorkflowDefinition<StatefulInput, StatefulOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "stateful-workflow" else "stateful-workflow:$kindSuffix"
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
            allKeys = keys,
        )
    }
}

/**
 * Task scheduling workflow - schedules tasks and aggregates results.
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 * @param taskKindSuffix Optional suffix to append to task kinds for test isolation
 */
class TaskSchedulingWorkflow(
    kindSuffix: String = "",
    private val taskKindSuffix: String = "",
) : WorkflowDefinition<TaskSchedulingInput, TaskSchedulingOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "task-scheduling-workflow" else "task-scheduling-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    private val addTaskKind = if (taskKindSuffix.isEmpty()) "add-task" else "add-task:$taskKindSuffix"

    override suspend fun execute(ctx: WorkflowContext, input: TaskSchedulingInput): TaskSchedulingOutput {
        var sum = 0
        for (num in input.numbers) {
            val result = ctx.schedule<AddTaskOutput>(
                kind = addTaskKind,
                input = AddTaskInput(a = sum, b = num),
            )
            sum = result.result
        }
        return TaskSchedulingOutput(sum = sum)
    }
}

/**
 * Run operation workflow - demonstrates ctx.run().
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class RunOperationWorkflow(kindSuffix: String = "") : WorkflowDefinition<EchoInput, EchoOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "run-operation-workflow" else "run-operation-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: EchoInput): EchoOutput {
        // Use run to record an operation
        val uppercased = ctx.run("uppercase") {
            input.message.uppercase()
        }

        return EchoOutput(
            message = uppercased,
            timestamp = ctx.currentTimeMillis(),
        )
    }
}

// --- Random/Sleep/Promise Data Classes ---

data class RandomInput(val count: Int)
data class RandomOutput(
    val uuids: List<String>,
    val randomInts: List<Int>,
    val randomDoubles: List<Double>,
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
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class RandomWorkflow(kindSuffix: String = "") : WorkflowDefinition<RandomInput, RandomOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "random-workflow" else "random-workflow:$kindSuffix"
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
            randomDoubles = randomDoubles,
        )
    }
}

/**
 * Sleep workflow - tests durable timers.
 * @param kindSuffix Optional suffix for test isolation
 */
class SleepWorkflow(kindSuffix: String = "") : WorkflowDefinition<SleepInput, SleepOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "sleep-workflow" else "sleep-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: SleepInput): SleepOutput {
        val startTime = ctx.currentTimeMillis()
        ctx.sleep(Duration.ofMillis(input.durationMs))
        val endTime = ctx.currentTimeMillis()
        return SleepOutput(startTime = startTime, endTime = endTime, sleptMs = input.durationMs)
    }
}

/**
 * Promise workflow - tests durable promise creation.
 * Creates a promise and returns information about it.
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class PromiseWorkflow(kindSuffix: String = "") : WorkflowDefinition<PromiseInput, PromiseOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "promise-workflow" else "promise-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: PromiseInput): PromiseOutput {
        // Create a durable promise (result not used, just creates the promise for external resolution)
        ctx.promise<String>(input.promiseName, Duration.ofSeconds(30))

        return PromiseOutput(
            promiseName = input.promiseName,
            created = true,
        )
    }
}

/**
 * Await promise workflow - creates a promise and waits for it to be resolved.
 * Used for testing external promise resolution.
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class AwaitPromiseWorkflow(kindSuffix: String = "") : WorkflowDefinition<AwaitPromiseInput, AwaitPromiseOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "await-promise-workflow" else "await-promise-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: AwaitPromiseInput): AwaitPromiseOutput {
        // Create a durable promise and wait for it
        val promise = ctx.promise<String>(input.promiseName, Duration.ofSeconds(60))
        val resolvedValue = promise.await()

        return AwaitPromiseOutput(
            promiseName = input.promiseName,
            resolvedValue = resolvedValue,
        )
    }
}

// --- Schema Test Workflows ---

/**
 * Input for schema test workflow.
 */
data class SchemaTestInput(
    val orderId: String,
    val amount: Double,
    val customerEmail: String? = null,
)

/**
 * Output for schema test workflow.
 */
data class SchemaTestOutput(
    val success: Boolean,
    val transactionId: String?,
)

/**
 * Workflow with explicit JSON Schema definitions.
 * Used to test that schemas are properly passed to the server.
 */
class SchemaTestWorkflow : WorkflowDefinition<SchemaTestInput, SchemaTestOutput>() {
    override val kind = "schema-test-workflow"
    override val name = "Schema Test Workflow"
    override val version = SemanticVersion(1, 0, 0)
    override val description = "A workflow with explicit JSON Schema for testing"

    // Explicit JSON Schema for input
    override val inputSchema: String = """
        {
            "type": "object",
            "properties": {
                "orderId": { "type": "string", "description": "The order ID" },
                "amount": { "type": "number", "description": "Amount in dollars" },
                "customerEmail": { "type": "string", "format": "email", "description": "Customer email (optional)" }
            },
            "required": ["orderId", "amount"]
        }
    """.trimIndent()

    // Explicit JSON Schema for output
    override val outputSchema: String = """
        {
            "type": "object",
            "properties": {
                "success": { "type": "boolean" },
                "transactionId": { "type": "string" }
            },
            "required": ["success"]
        }
    """.trimIndent()

    override suspend fun execute(ctx: WorkflowContext, input: SchemaTestInput): SchemaTestOutput {
        return SchemaTestOutput(
            success = true,
            transactionId = "txn-${input.orderId}",
        )
    }
}

// --- Child Workflow Fixtures ---

data class ChildInput(val value: Int)
data class ChildOutput(val result: Int, val workflowId: String)

/**
 * Simple child workflow that doubles a value.
 * Used as a child in parent workflow tests.
 */
class ChildWorkflow : WorkflowDefinition<ChildInput, ChildOutput>() {
    override val kind = "child-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: ChildInput): ChildOutput {
        return ChildOutput(
            result = input.value * 2,
            workflowId = ctx.workflowExecutionId.toString(),
        )
    }
}

data class ParentInput(val value: Int)
data class ParentOutput(val originalValue: Int, val childResult: Int, val parentWorkflowId: String)

/**
 * Parent workflow that executes a child workflow.
 */
class ParentWorkflow : WorkflowDefinition<ParentInput, ParentOutput>() {
    override val kind = "parent-workflow"
    override val version = SemanticVersion(1, 0, 0)

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(ctx: WorkflowContext, input: ParentInput): ParentOutput {
        val childOutput: Map<String, Any?> = ctx.scheduleWorkflow(
            name = "child-execution-${input.value}",
            kind = "child-workflow",
            input = ChildInput(value = input.value),
        )
        return ParentOutput(
            originalValue = input.value,
            childResult = (childOutput["result"] as Number).toInt(),
            parentWorkflowId = ctx.workflowExecutionId.toString(),
        )
    }
}

data class FailingChildInput(val message: String)

/**
 * Child workflow that always fails.
 * Used to test error handling in parent workflows.
 */
class FailingChildWorkflow : WorkflowDefinition<FailingChildInput, Unit>() {
    override val kind = "failing-child-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: FailingChildInput) {
        throw RuntimeException(input.message)
    }
}

data class ParentWithFailingChildInput(val message: String)
data class ParentWithFailingChildOutput(val errorCaught: Boolean, val errorMessage: String?)

/**
 * Parent workflow that executes a failing child workflow and handles the error.
 */
class ParentWithFailingChildWorkflow : WorkflowDefinition<ParentWithFailingChildInput, ParentWithFailingChildOutput>() {
    override val kind = "parent-with-failing-child-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(
        ctx: WorkflowContext,
        input: ParentWithFailingChildInput,
    ): ParentWithFailingChildOutput {
        return try {
            ctx.scheduleWorkflow<Unit>(
                name = "failing-child-execution",
                kind = "failing-child-workflow",
                input = FailingChildInput(message = input.message),
            )
            ParentWithFailingChildOutput(errorCaught = false, errorMessage = null)
        } catch (e: Exception) {
            ParentWithFailingChildOutput(errorCaught = true, errorMessage = e.message)
        }
    }
}

data class NestedInput(val depth: Int, val value: String)
data class NestedOutput(val result: String, val levels: Int)

/**
 * Nested child workflow that calls itself recursively.
 * At depth 1 returns "leaf:value", otherwise wraps child result.
 */
class NestedChildWorkflow : WorkflowDefinition<NestedInput, NestedOutput>() {
    override val kind = "nested-child-workflow"
    override val version = SemanticVersion(1, 0, 0)

    @Suppress("UNCHECKED_CAST")
    override suspend fun execute(ctx: WorkflowContext, input: NestedInput): NestedOutput {
        if (input.depth <= 1) {
            return NestedOutput(result = "leaf:${input.value}", levels = 1)
        }

        val childOutput: Map<String, Any?> = ctx.scheduleWorkflow(
            name = "nested-child-depth-${input.depth - 1}",
            kind = "nested-child-workflow",
            input = NestedInput(depth = input.depth - 1, value = input.value),
        )
        val childResult = childOutput["result"] as String
        val childLevels = (childOutput["levels"] as Number).toInt()
        return NestedOutput(
            result = "level${input.depth}:$childResult",
            levels = childLevels + 1,
        )
    }
}

// --- Comprehensive Test Workflows ---

data class ComprehensiveInput(val value: Int)
data class ComprehensiveOutput(
    val inputValue: Int,
    val runResult: Int,
    val stateSet: Boolean,
    val stateMatches: Boolean,
    val stateRetrieved: Map<String, Any?>,
    val tripleResult: Int,
    val testsPassedCount: Int,
)

/**
 * Comprehensive workflow that tests multiple features in one execution.
 * Tests: basic input, ctx.run operations, state set/get, multiple operations.
 * @param kindSuffix Optional suffix to append to the workflow kind for test isolation
 */
class ComprehensiveWorkflow(kindSuffix: String = "") : WorkflowDefinition<ComprehensiveInput, ComprehensiveOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "comprehensive-workflow" else "comprehensive-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: ComprehensiveInput): ComprehensiveOutput {
        var testsPassed = 0

        // Test 1: Basic input
        val inputValue = input.value
        testsPassed++

        // Test 2: ctx.run operation
        val runResult = ctx.run("double-value") {
            input.value * 2
        }
        testsPassed++

        // Test 3: State set
        val stateData = mapOf(
            "counter" to input.value,
            "message" to "state test",
            "nested" to mapOf("a" to 1, "b" to 2),
        )
        ctx.set("test-state", stateData)
        testsPassed++

        // Test 4: State get
        val retrieved: Map<String, Any?>? = ctx.get("test-state")
        val stateMatches = retrieved == stateData
        testsPassed++

        // Test 5: Multiple operations
        val tripleResult = ctx.run("triple-value") {
            input.value * 3
        }
        testsPassed++

        return ComprehensiveOutput(
            inputValue = inputValue,
            runResult = runResult,
            stateSet = true,
            stateMatches = stateMatches,
            stateRetrieved = retrieved ?: emptyMap(),
            tripleResult = tripleResult,
            testsPassedCount = testsPassed,
        )
    }
}

// --- Error Test Workflows ---

data class ErrorMessageInput(val errorMessage: String, val includeDetails: Boolean = false)

/**
 * Workflow that fails with a specific error message.
 * Used to test that error messages are preserved.
 */
class ErrorMessageWorkflow : WorkflowDefinition<ErrorMessageInput, Unit>() {
    override val kind = "error-message-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: ErrorMessageInput) {
        if (input.includeDetails) {
            throw RuntimeException("${input.errorMessage} [workflow=${ctx.workflowExecutionId}]")
        } else {
            throw RuntimeException(input.errorMessage)
        }
    }
}

// --- Parallel Task Workflow Fixtures (matching Python SDK) ---

data class FanOutFanInInput(val items: List<String>)
data class FanOutFanInOutput(
    val inputCount: Int,
    val outputCount: Int,
    val processedItems: List<String>,
    val totalLength: Int,
)

/**
 * Fan-out/fan-in workflow - schedules parallel echo tasks and aggregates results.
 * Matches Python SDK's fan-out-fan-in-workflow pattern.
 */
class FanOutFanInWorkflow : WorkflowDefinition<FanOutFanInInput, FanOutFanInOutput>() {
    override val kind = "fan-out-fan-in-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: FanOutFanInInput): FanOutFanInOutput {
        // Fan-out: schedule echo tasks for each item
        val deferreds = input.items.map { item ->
            ctx.scheduleAsync(
                kind = "echo-task",
                input = EchoTaskInput(message = item),
                outputClass = EchoTaskOutput::class,
            )
        }

        // Fan-in: collect all results
        val results = awaitAll(deferreds)
        val processedItems = results.map { it.message }

        return FanOutFanInOutput(
            inputCount = input.items.size,
            outputCount = results.size,
            processedItems = processedItems,
            totalLength = processedItems.sumOf { it.length },
        )
    }
}

data class LargeBatchInput(val count: Int)
data class LargeBatchOutput(
    val taskCount: Int,
    val total: Int,
    val minValue: Int,
    val maxValue: Int,
)

/**
 * Large batch workflow - processes many tasks in parallel.
 * Matches Python SDK's large-batch-workflow pattern.
 * Each task computes i + 1 for i in range(count).
 */
class LargeBatchWorkflow : WorkflowDefinition<LargeBatchInput, LargeBatchOutput>() {
    override val kind = "large-batch-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: LargeBatchInput): LargeBatchOutput {
        // Schedule batch of tasks: each computes (i + 1) for i in 0..count-1
        // Results are [1, 2, 3, ..., count]
        val deferreds = (0 until input.count).map { i ->
            // i + 1
            ctx.scheduleAsync(
                kind = "add-task",
                input = AddTaskInput(a = i, b = 1),
                outputClass = AddTaskOutput::class,
            )
        }

        // Await all
        val results = awaitAll(deferreds)
        val values = results.map { it.result }

        // sum(1..count) = count*(count+1)/2
        return LargeBatchOutput(
            taskCount = results.size,
            total = values.sum(),
            minValue = values.minOrNull() ?: 0,
            maxValue = values.maxOrNull() ?: 0,
        )
    }
}

data class MixedParallelInput(val dummy: Boolean = true)
data class MixedParallelOutput(
    val success: Boolean,
    val phase1Results: List<String>,
    val timerFired: Boolean,
    val phase3Results: List<Int>,
)

/**
 * Mixed parallel workflow - three-phase workflow with tasks and timers.
 * Matches Python SDK's mixed-parallel-workflow pattern.
 *
 * Phase 1: Two parallel echo tasks
 * Phase 2: Timer (100ms)
 * Phase 3: Three parallel add tasks (i + i for i in [0,1,2])
 */
class MixedParallelWorkflow : WorkflowDefinition<MixedParallelInput, MixedParallelOutput>() {
    override val kind = "mixed-parallel-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: MixedParallelInput): MixedParallelOutput {
        // Phase 1: Two parallel echo tasks
        val phase1Deferreds = listOf("item1", "item2").map { item ->
            ctx.scheduleAsync(
                kind = "echo-task",
                input = EchoTaskInput(message = item),
                outputClass = EchoTaskOutput::class,
            )
        }
        val phase1Results = awaitAll(phase1Deferreds).map { it.message }

        // Phase 2: Timer
        ctx.sleep(Duration.ofMillis(100))
        val timerFired = true

        // Phase 3: Three parallel add tasks (i + i for i in [0,1,2] = [0, 2, 4])
        val phase3Deferreds = listOf(0, 1, 2).map { i ->
            ctx.scheduleAsync(
                kind = "add-task",
                input = AddTaskInput(a = i, b = i),
                outputClass = AddTaskOutput::class,
            )
        }
        val phase3Results = awaitAll(phase3Deferreds).map { it.result }

        return MixedParallelOutput(
            success = true,
            phase1Results = phase1Results,
            timerFired = timerFired,
            phase3Results = phase3Results,
        )
    }
}

// --- Timer Test Workflow ---

data class ShortTimerInput(val durationMs: Long)
data class ShortTimerOutput(val elapsedMs: Long, val timerFired: Boolean)

/** Short timer workflow for testing quick timers. */
class ShortTimerWorkflow : WorkflowDefinition<ShortTimerInput, ShortTimerOutput>() {
    override val kind = "short-timer-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: ShortTimerInput): ShortTimerOutput {
        ctx.sleep(Duration.ofMillis(input.durationMs))
        return ShortTimerOutput(elapsedMs = input.durationMs, timerFired = true)
    }
}

// --- Replay Test Workflows ---

data class MixedCommandsInput(val value: Int, val runCount: Int)
data class MixedCommandsOutput(
    val initialValue: Int,
    val runResults: List<Int>,
    val timerFired: Boolean,
    val taskResult: Int,
    val finalValue: Int,
)

/**
 * Workflow with mixed commands - operations, timers, and tasks interleaved.
 * Used for testing replay of mixed command types.
 */
class MixedCommandsWorkflow : WorkflowDefinition<MixedCommandsInput, MixedCommandsOutput>() {
    override val kind = "mixed-commands-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: MixedCommandsInput): MixedCommandsOutput {
        val runResults = mutableListOf<Int>()
        var currentValue = input.value

        // Run operations
        for (i in 1..input.runCount) {
            val result = ctx.run("run-$i") {
                currentValue + i
            }
            runResults.add(result)
            currentValue = result
        }

        // Timer
        ctx.sleep(Duration.ofMillis(50))

        // Task
        val taskResult = ctx.schedule<AddTaskOutput>(
            kind = "add-task",
            input = AddTaskInput(a = currentValue, b = 10),
        )

        return MixedCommandsOutput(
            initialValue = input.value,
            runResults = runResults,
            timerFired = true,
            taskResult = taskResult.result,
            finalValue = taskResult.result,
        )
    }
}

// --- Task Scheduler Workflow for Streaming Tests ---

data class TaskSchedulerInput(
    val taskName: String,
    val taskInput: Map<String, Any?>,
)
data class TaskSchedulerOutput(
    val taskCompleted: Boolean,
    val taskResult: Map<String, Any?>?,
)

/**
 * Workflow that schedules a task by name and returns its result.
 */
class TaskSchedulerWorkflow : WorkflowDefinition<TaskSchedulerInput, TaskSchedulerOutput>() {
    override val kind = "task-scheduler-workflow"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: TaskSchedulerInput): TaskSchedulerOutput {
        val result = ctx.schedule<Map<String, Any?>>(kind = input.taskName, input = input.taskInput)
        return TaskSchedulerOutput(taskCompleted = true, taskResult = result)
    }
}

// --- Signal Test Workflows ---

data class SignalWorkflowInput(val dummy: Boolean = true)
data class SignalWorkflowOutput(
    val signalName: String,
    val signalValue: Map<String, Any?>,
)

/**
 * Workflow that waits for a single signal and returns it.
 */
class SignalWorkflow(kindSuffix: String = "") : WorkflowDefinition<SignalWorkflowInput, SignalWorkflowOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "signal-workflow" else "signal-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: SignalWorkflowInput): SignalWorkflowOutput {
        val signal = ctx.waitForSignal<Map<String, Any?>>()
        return SignalWorkflowOutput(
            signalName = signal.name,
            signalValue = signal.value,
        )
    }
}

data class MultiSignalInput(val signalCount: Int)
data class MultiSignalOutput(
    val count: Int,
    val signals: List<Map<String, Any?>>,
)

/**
 * Workflow that waits for multiple signals.
 */
class MultiSignalWorkflow(kindSuffix: String = "") : WorkflowDefinition<MultiSignalInput, MultiSignalOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "multi-signal-workflow" else "multi-signal-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: MultiSignalInput): MultiSignalOutput {
        val signals = mutableListOf<Map<String, Any?>>()

        for (i in 0 until input.signalCount) {
            val signal = ctx.waitForSignal<Map<String, Any?>>()
            signals.add(mapOf("name" to signal.name, "value" to signal.value))
        }

        return MultiSignalOutput(
            count = signals.size,
            signals = signals,
        )
    }
}

data class SignalCheckInput(val dummy: Boolean = true)
data class SignalCheckOutput(
    val hasSignal: Boolean,
    val signals: List<Map<String, Any?>>,
)

/**
 * Workflow that uses hasSignal and drainSignals for non-blocking check.
 */
class SignalCheckWorkflow(kindSuffix: String = "") : WorkflowDefinition<SignalCheckInput, SignalCheckOutput>() {
    override val kind = if (kindSuffix.isEmpty()) "signal-check-workflow" else "signal-check-workflow:$kindSuffix"
    override val version = SemanticVersion(1, 0, 0)

    override suspend fun execute(ctx: WorkflowContext, input: SignalCheckInput): SignalCheckOutput {
        // Small delay to allow signals to arrive
        ctx.sleep(Duration.ofMillis(500))

        // Check if any signals are pending
        val hasSignal = ctx.hasSignal()

        // Drain all pending signals
        val signals = ctx.drainSignals<Map<String, Any?>>()

        return SignalCheckOutput(
            hasSignal = hasSignal,
            signals = signals.map { mapOf("name" to it.name, "value" to it.value) },
        )
    }
}
