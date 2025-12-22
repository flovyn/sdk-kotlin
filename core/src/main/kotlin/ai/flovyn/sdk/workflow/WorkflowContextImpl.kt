package ai.flovyn.sdk.workflow

import ai.flovyn.sdk.task.ScheduleTaskOptions
import ai.flovyn.sdk.serialization.JsonSerializer
import uniffi.flovyn_ffi.*
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Implementation of WorkflowContext that generates commands.
 *
 * The Rust core handles replay and determinism validation. This implementation
 * is responsible for:
 * - Generating commands from workflow operations
 * - Providing deterministic time and random values
 * - Managing workflow state
 */
internal class WorkflowContextImpl(
    override val workflowExecutionId: UUID,
    override val tenantId: UUID,
    override val input: Map<String, Any?>,
    private val timestampMs: Long,
    private val randomSeed: ByteArray,
    private val initialState: Map<String, ByteArray>,
    private val serializer: JsonSerializer,
    private val isReplaying: Boolean = false
) : WorkflowContext {

    private val commands = mutableListOf<FfiWorkflowCommand>()
    private var sequenceNumber = 0
    private val seededRandom = SeededRandom(randomSeed)

    // Current state (mutable during execution)
    private val currentState = initialState.toMutableMap()

    // Cancellation tracking
    private var cancellationRequested = false

    override fun currentTimeMillis(): Long = timestampMs

    override fun randomUUID(): UUID = seededRandom.nextUUID()

    override fun random(): Random = seededRandom

    override suspend fun <T> run(name: String, block: suspend () -> T): T {
        // Execute the operation
        val result = block()

        // Record the command
        commands.add(
            FfiWorkflowCommand.RecordOperation(
                operationName = name,
                result = serializer.serialize(result)
            )
        )

        return result
    }

    override suspend fun <T> runAsync(name: String, block: suspend () -> T): Deferred<T> {
        val deferred = DeferredImpl<T>()

        // Execute the operation
        val result = block()

        // Record the command
        commands.add(
            FfiWorkflowCommand.RecordOperation(
                operationName = name,
                result = serializer.serialize(result)
            )
        )

        deferred.complete(result)
        return deferred
    }

    override suspend fun <T : Any> schedule(
        taskType: String,
        input: Any?,
        outputClass: KClass<T>,
        options: ScheduleTaskOptions
    ): T {
        val taskId = generateTaskId()

        // Record command for task execution
        commands.add(
            FfiWorkflowCommand.ScheduleTask(
                taskExecutionId = taskId,
                taskType = taskType,
                input = serializer.serialize(input),
                prioritySeconds = options.prioritySeconds,
                maxRetries = options.maxRetries?.toUInt(),
                timeoutMs = options.timeoutSeconds?.let { it * 1000L },
                queue = null
            )
        )

        // Suspend workflow until task completes
        throw WorkflowSuspendedException("Waiting for task $taskId")
    }

    override suspend fun <T : Any> scheduleAsync(
        taskType: String,
        input: Any?,
        outputClass: KClass<T>
    ): Deferred<T> {
        val taskId = generateTaskId()

        // Record command for task execution
        commands.add(
            FfiWorkflowCommand.ScheduleTask(
                taskExecutionId = taskId,
                taskType = taskType,
                input = serializer.serialize(input),
                prioritySeconds = 0,
                maxRetries = null,
                timeoutMs = null,
                queue = null
            )
        )

        // Return a pending deferred
        return PendingDeferredImpl(taskId)
    }

    override suspend fun <T> scheduleWorkflow(
        name: String,
        kind: String,
        input: Any?,
        taskQueue: String,
        prioritySeconds: Int
    ): T {
        val childExecutionId = "$workflowExecutionId-$name"

        // Record command
        commands.add(
            FfiWorkflowCommand.ScheduleChildWorkflow(
                name = name,
                kind = kind,
                childExecutionId = childExecutionId,
                input = serializer.serialize(input),
                taskQueue = taskQueue,
                prioritySeconds = prioritySeconds
            )
        )

        throw WorkflowSuspendedException("Waiting for child workflow $childExecutionId")
    }

    override suspend fun <T> scheduleWorkflowAsync(
        name: String,
        kind: String,
        input: Any?,
        taskQueue: String,
        prioritySeconds: Int
    ): Deferred<T> {
        val childExecutionId = "$workflowExecutionId-$name"

        // Record command
        commands.add(
            FfiWorkflowCommand.ScheduleChildWorkflow(
                name = name,
                kind = kind,
                childExecutionId = childExecutionId,
                input = serializer.serialize(input),
                taskQueue = taskQueue,
                prioritySeconds = prioritySeconds
            )
        )

        return PendingDeferredImpl(childExecutionId)
    }

    override suspend fun <T> get(key: String): T? {
        val bytes = currentState[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return serializer.deserialize(bytes, Any::class.java) as T?
    }

    override suspend fun <T> set(key: String, value: T) {
        val bytes = serializer.serialize(value)
        currentState[key] = bytes
        commands.add(FfiWorkflowCommand.SetState(key = key, value = bytes))
    }

    override suspend fun clear(key: String) {
        currentState.remove(key)
        commands.add(FfiWorkflowCommand.ClearState(key = key))
    }

    override suspend fun clearAll() {
        currentState.keys.toList().forEach { clear(it) }
    }

    override suspend fun stateKeys(): List<String> {
        return currentState.keys.toList()
    }

    override suspend fun sleep(duration: Duration) {
        val timerId = "timer-${++sequenceNumber}"

        // Record timer command
        commands.add(
            FfiWorkflowCommand.StartTimer(
                timerId = timerId,
                durationMs = duration.toMillis()
            )
        )

        throw WorkflowSuspendedException("Waiting for timer $timerId")
    }

    override suspend fun <T> promise(name: String, timeout: Duration?): DurablePromise<T> {
        val promiseId = "promise-$name"

        // Record promise creation command
        commands.add(
            FfiWorkflowCommand.CreatePromise(
                promiseId = promiseId,
                timeoutMs = timeout?.toMillis()
            )
        )

        return PendingDurablePromise(name)
    }

    override fun isCancellationRequested(): Boolean = cancellationRequested

    override suspend fun checkCancellation() {
        if (cancellationRequested) {
            throw WorkflowCancelledException("Workflow cancellation requested")
        }
    }

    /**
     * Mark cancellation as requested.
     */
    internal fun requestCancellation() {
        cancellationRequested = true
    }

    private fun generateTaskId(): String = "task-${++sequenceNumber}"

    /**
     * Get all commands generated during execution.
     */
    fun getCommands(): List<FfiWorkflowCommand> = commands.toList()
}

/**
 * Seeded random number generator for deterministic workflow execution.
 */
internal class SeededRandom(seed: ByteArray) : Random() {
    private val random = java.util.Random(seed.fold(0L) { acc, byte -> acc * 31 + byte })

    override fun nextBits(bitCount: Int): Int = random.nextInt() ushr (32 - bitCount)

    fun nextUUID(): UUID {
        val mostSigBits = random.nextLong()
        val leastSigBits = random.nextLong()
        // UUID version 4 (random) with variant 2 (IETF)
        return UUID(
            (mostSigBits and -0xf001L) or 0x4000L,
            (leastSigBits and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        )
    }
}

/**
 * Implementation of Deferred for completed async operations.
 */
internal class DeferredImpl<T> : Deferred<T> {
    private var result: T? = null
    private var exception: Throwable? = null
    private var completed = false

    override suspend fun await(): T {
        if (!completed) {
            throw WorkflowSuspendedException("Deferred not completed")
        }
        exception?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun isCompleted(): Boolean = completed

    fun complete(value: T) {
        result = value
        completed = true
    }

    fun completeExceptionally(e: Throwable) {
        exception = e
        completed = true
    }
}

/**
 * Implementation of Deferred for pending async operations.
 */
internal class PendingDeferredImpl<T>(private val id: String) : Deferred<T> {
    override suspend fun await(): T {
        throw WorkflowSuspendedException("Waiting for $id")
    }

    override fun isCompleted(): Boolean = false
}

/**
 * Pending durable promise (not yet resolved).
 */
private class PendingDurablePromise<T>(
    override val name: String
) : DurablePromise<T> {
    override suspend fun await(): T = throw WorkflowSuspendedException("Promise '$name' not resolved")
    override fun isCompleted(): Boolean = false
}

/**
 * Exception thrown when a workflow needs to suspend.
 */
class WorkflowSuspendedException(message: String) : Exception(message)

/**
 * Exception thrown when a workflow is cancelled.
 */
class WorkflowCancelledException(message: String) : Exception(message)

/**
 * Exception thrown when a task fails.
 */
class TaskFailedException(
    val taskId: String,
    message: String
) : Exception("Task '$taskId' failed: $message")

/**
 * Exception thrown when a child workflow fails.
 */
class ChildWorkflowFailedException(
    val childExecutionId: String,
    message: String
) : Exception("Child workflow '$childExecutionId' failed: $message")
