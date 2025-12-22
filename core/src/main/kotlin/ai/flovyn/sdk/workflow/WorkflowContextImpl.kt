package ai.flovyn.sdk.workflow

import ai.flovyn.sdk.task.ScheduleTaskOptions
import ai.flovyn.sdk.serialization.JsonSerializer
import uniffi.flovyn_ffi.*
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Implementation of WorkflowContext that delegates to FFI context.
 *
 * The Rust FFI layer handles all replay logic:
 * - Pre-filters events by type for O(1) lookup
 * - Validates determinism during replay
 * - Returns cached results for replayed operations
 * - Only generates commands for NEW operations
 */
internal class WorkflowContextImpl(
    private val ffiContext: FfiWorkflowContext,
    private val serializer: JsonSerializer
) : WorkflowContext {

    override val workflowExecutionId: UUID
        get() = UUID.fromString(ffiContext.workflowExecutionId())

    override val tenantId: UUID
        get() = UUID.randomUUID() // TODO: Get from ffiContext when available

    override val input: Map<String, Any?>
        get() = emptyMap() // Input is handled externally

    override fun currentTimeMillis(): Long = ffiContext.currentTimeMillis()

    override fun randomUUID(): UUID = UUID.fromString(ffiContext.randomUuid())

    override fun random(): Random = FfiBasedRandom(ffiContext)

    override suspend fun <T> run(name: String, block: suspend () -> T): T {
        return when (val result = ffiContext.runOperation(name)) {
            is FfiOperationResult.Cached -> {
                @Suppress("UNCHECKED_CAST")
                serializer.deserialize(result.value, Any::class.java) as T
            }
            is FfiOperationResult.Execute -> {
                val value = block()
                ffiContext.recordOperationResult(name, serializer.serialize(value))
                value
            }
        }
    }

    override suspend fun <T> runAsync(name: String, block: suspend () -> T): Deferred<T> {
        val deferred = DeferredImpl<T>()
        val value = run(name, block)
        deferred.complete(value)
        return deferred
    }

    override suspend fun <T : Any> schedule(
        taskType: String,
        input: Any?,
        outputClass: KClass<T>,
        options: ScheduleTaskOptions
    ): T {
        val inputBytes = serializer.serialize(input)

        return when (val result = ffiContext.scheduleTask(
            taskType = taskType,
            input = inputBytes,
            queue = null,
            timeoutMs = options.timeoutSeconds?.let { it * 1000L }
        )) {
            is FfiTaskResult.Completed -> {
                serializer.deserialize(result.output, outputClass.java)
            }
            is FfiTaskResult.Failed -> {
                throw TaskFailedException("unknown", result.error)
            }
            is FfiTaskResult.Pending -> {
                throw WorkflowSuspendedException("Waiting for task: ${result.taskExecutionId}")
            }
        }
    }

    override suspend fun <T : Any> scheduleAsync(
        taskType: String,
        input: Any?,
        outputClass: KClass<T>
    ): Deferred<T> {
        val inputBytes = serializer.serialize(input)

        return when (val result = ffiContext.scheduleTask(
            taskType = taskType,
            input = inputBytes,
            queue = null,
            timeoutMs = null
        )) {
            is FfiTaskResult.Completed -> {
                val value = serializer.deserialize(result.output, outputClass.java)
                DeferredImpl<T>().apply { complete(value) }
            }
            is FfiTaskResult.Failed -> {
                DeferredImpl<T>().apply { completeExceptionally(TaskFailedException("unknown", result.error)) }
            }
            is FfiTaskResult.Pending -> {
                PendingDeferredImpl(result.taskExecutionId)
            }
        }
    }

    override suspend fun <T> scheduleWorkflow(
        name: String,
        kind: String,
        input: Any?,
        taskQueue: String,
        prioritySeconds: Int
    ): T {
        val inputBytes = serializer.serialize(input)

        return when (val result = ffiContext.scheduleChildWorkflow(
            name = name,
            kind = kind,
            input = inputBytes,
            taskQueue = taskQueue.ifEmpty { null },
            prioritySeconds = prioritySeconds
        )) {
            is FfiChildWorkflowResult.Completed -> {
                @Suppress("UNCHECKED_CAST")
                serializer.deserialize(result.output, Any::class.java) as T
            }
            is FfiChildWorkflowResult.Failed -> {
                throw ChildWorkflowFailedException("unknown", result.error)
            }
            is FfiChildWorkflowResult.Pending -> {
                throw WorkflowSuspendedException("Waiting for child workflow: ${result.childExecutionId}")
            }
        }
    }

    override suspend fun <T> scheduleWorkflowAsync(
        name: String,
        kind: String,
        input: Any?,
        taskQueue: String,
        prioritySeconds: Int
    ): Deferred<T> {
        val inputBytes = serializer.serialize(input)

        return when (val result = ffiContext.scheduleChildWorkflow(
            name = name,
            kind = kind,
            input = inputBytes,
            taskQueue = taskQueue.ifEmpty { null },
            prioritySeconds = prioritySeconds
        )) {
            is FfiChildWorkflowResult.Completed -> {
                @Suppress("UNCHECKED_CAST")
                val value = serializer.deserialize(result.output, Any::class.java) as T
                DeferredImpl<T>().apply { complete(value) }
            }
            is FfiChildWorkflowResult.Failed -> {
                DeferredImpl<T>().apply { completeExceptionally(ChildWorkflowFailedException("unknown", result.error)) }
            }
            is FfiChildWorkflowResult.Pending -> {
                PendingDeferredImpl(result.childExecutionId)
            }
        }
    }

    override suspend fun <T> get(key: String): T? {
        val bytes = ffiContext.getState(key) ?: return null
        @Suppress("UNCHECKED_CAST")
        return serializer.deserialize(bytes, Any::class.java) as T?
    }

    override suspend fun <T> set(key: String, value: T) {
        val bytes = serializer.serialize(value)
        ffiContext.setState(key, bytes)
    }

    override suspend fun clear(key: String) {
        ffiContext.clearState(key)
    }

    override suspend fun clearAll() {
        ffiContext.stateKeys().forEach { ffiContext.clearState(it) }
    }

    override suspend fun stateKeys(): List<String> {
        return ffiContext.stateKeys()
    }

    override suspend fun sleep(duration: Duration) {
        when (val result = ffiContext.startTimer(duration.toMillis())) {
            is FfiTimerResult.Fired -> {
                // Timer already fired during replay - continue execution
            }
            is FfiTimerResult.Pending -> {
                throw WorkflowSuspendedException("Waiting for timer: ${result.timerId}")
            }
        }
    }

    override suspend fun <T> promise(name: String, timeout: Duration?): DurablePromise<T> {
        return when (val result = ffiContext.createPromise(name, timeout?.toMillis())) {
            is FfiPromiseResult.Resolved -> {
                @Suppress("UNCHECKED_CAST")
                ResolvedDurablePromise(name, serializer.deserialize(result.value, Any::class.java) as T)
            }
            is FfiPromiseResult.Rejected -> {
                RejectedDurablePromise(name, result.error)
            }
            is FfiPromiseResult.TimedOut -> {
                TimedOutDurablePromise(name)
            }
            is FfiPromiseResult.Pending -> {
                throw WorkflowSuspendedException("Waiting for promise: ${result.promiseId}")
            }
        }
    }

    override fun isCancellationRequested(): Boolean = ffiContext.isCancellationRequested()

    override suspend fun checkCancellation() {
        if (isCancellationRequested()) {
            throw WorkflowCancelledException("Workflow cancellation requested")
        }
    }

    /**
     * Request cancellation (called when CancelWorkflow job is received).
     */
    internal fun requestCancellation() {
        ffiContext.requestCancellation()
    }
}

/**
 * Random implementation backed by FFI context.
 */
internal class FfiBasedRandom(private val ffiContext: FfiWorkflowContext) : Random() {
    override fun nextBits(bitCount: Int): Int {
        val value = (ffiContext.random() * Int.MAX_VALUE).toInt()
        return value ushr (32 - bitCount)
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
 * Resolved durable promise.
 */
private class ResolvedDurablePromise<T>(private val promiseName: String, private val value: T) : DurablePromise<T> {
    override val name: String = promiseName
    override suspend fun await(): T = value
    override fun isCompleted(): Boolean = true
}

/**
 * Rejected durable promise.
 */
private class RejectedDurablePromise<T>(private val promiseName: String, private val error: String) : DurablePromise<T> {
    override val name: String = promiseName
    override suspend fun await(): T = throw PromiseRejectedException(promiseName, error)
    override fun isCompleted(): Boolean = true
}

/**
 * Timed out durable promise.
 */
private class TimedOutDurablePromise<T>(private val promiseName: String) : DurablePromise<T> {
    override val name: String = promiseName
    override suspend fun await(): T = throw PromiseTimeoutException(promiseName)
    override fun isCompleted(): Boolean = true
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


/**
 * Exception thrown when a determinism violation is detected.
 */
class DeterminismViolationException(message: String) : Exception(message)
