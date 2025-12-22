package ai.flovyn.sdk.workflow

import ai.flovyn.sdk.task.ScheduleTaskOptions
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Context for workflow execution.
 *
 * Provides deterministic operations for workflows - all operations that could vary
 * between executions (time, random, external calls) must go through this context
 * to ensure replay determinism.
 */
interface WorkflowContext {
    /** Unique identifier for this workflow execution */
    val workflowExecutionId: UUID

    /** The input data for this workflow (as a map) */
    val input: Map<String, Any?>

    /** The tenant ID owning this workflow */
    val tenantId: UUID

    /**
     * Get the current time in milliseconds.
     * This is deterministic - returns the same value on replay.
     */
    fun currentTimeMillis(): Long

    /**
     * Generate a deterministic UUID.
     * Uses the workflow's random seed, so it's consistent on replay.
     */
    fun randomUUID(): UUID

    /**
     * Get a deterministic random number generator.
     * Seeded from the workflow execution, consistent on replay.
     */
    fun random(): Random

    /**
     * Run an operation and record its result for deterministic replay.
     *
     * Use this for any non-deterministic operation that doesn't call external systems
     * (e.g., complex calculations that might vary, local file reads).
     */
    suspend fun <T> run(name: String, block: suspend () -> T): T

    /**
     * Schedule a task for async execution and return a Deferred.
     *
     * @param name Operation name for replay identification
     * @param block The operation to execute
     * @return A Deferred that can be awaited later
     */
    suspend fun <T> runAsync(name: String, block: suspend () -> T): Deferred<T>

    /**
     * Schedule a task for execution (base method with explicit type).
     *
     * @param taskType The type of task to schedule
     * @param input The task input
     * @param outputClass The class of the expected output type
     * @param options Task scheduling options
     * @return The task output
     */
    suspend fun <T : Any> schedule(
        taskType: String,
        input: Any?,
        outputClass: KClass<T>,
        options: ScheduleTaskOptions = ScheduleTaskOptions.DEFAULT
    ): T

    /**
     * Schedule a task for async execution (base method with explicit type).
     *
     * @param taskType The type of task to schedule
     * @param input The task input
     * @param outputClass The class of the expected output type
     * @return A Deferred that resolves to the task output
     */
    suspend fun <T : Any> scheduleAsync(
        taskType: String,
        input: Any?,
        outputClass: KClass<T>
    ): Deferred<T>

    /**
     * Schedule a child workflow for execution.
     *
     * @param name Unique name for this child execution
     * @param kind The workflow kind to execute
     * @param input The workflow input
     * @param taskQueue Optional task queue (defaults to same queue)
     * @param prioritySeconds Priority offset in seconds
     * @return The workflow output
     */
    suspend fun <T> scheduleWorkflow(
        name: String,
        kind: String,
        input: Any? = null,
        taskQueue: String = "",
        prioritySeconds: Int = 0
    ): T

    /**
     * Schedule a child workflow for async execution.
     *
     * @param name Unique name for this child execution
     * @param kind The workflow kind to execute
     * @param input The workflow input
     * @param taskQueue Optional task queue (defaults to same queue)
     * @param prioritySeconds Priority offset in seconds
     * @return A Deferred that resolves to the workflow output
     */
    suspend fun <T> scheduleWorkflowAsync(
        name: String,
        kind: String,
        input: Any? = null,
        taskQueue: String = "",
        prioritySeconds: Int = 0
    ): Deferred<T>

    // --- State Management ---

    /**
     * Get a value from workflow state.
     */
    suspend fun <T> get(key: String): T?

    /**
     * Set a value in workflow state.
     */
    suspend fun <T> set(key: String, value: T)

    /**
     * Clear a value from workflow state.
     */
    suspend fun clear(key: String)

    /**
     * Clear all workflow state.
     */
    suspend fun clearAll()

    /**
     * Get all state keys.
     */
    suspend fun stateKeys(): List<String>

    // --- Timers ---

    /**
     * Sleep for a duration (durable timer).
     * The workflow will be suspended and resumed after the duration.
     */
    suspend fun sleep(duration: Duration)

    // --- Promises ---

    /**
     * Create a durable promise that can be resolved externally.
     *
     * @param name Unique name for this promise
     * @param timeout Optional timeout duration
     * @return A DurablePromise that can be awaited
     */
    suspend fun <T> promise(name: String, timeout: Duration? = null): DurablePromise<T>

    // --- Cancellation ---

    /**
     * Check if cancellation has been requested.
     */
    fun isCancellationRequested(): Boolean

    /**
     * Throw CancellationException if cancellation has been requested.
     * Call this at safe points in the workflow to handle cancellation.
     */
    suspend fun checkCancellation()
}

/**
 * Await all deferreds and return their results.
 */
suspend fun <T> awaitAll(vararg deferreds: Deferred<T>): List<T> =
    deferreds.map { it.await() }

/**
 * Await all deferreds and return their results.
 */
suspend fun <T> awaitAll(deferreds: List<Deferred<T>>): List<T> =
    deferreds.map { it.await() }

/**
 * Schedule a task for execution with reified type parameter.
 */
suspend inline fun <reified T : Any> WorkflowContext.schedule(
    taskType: String,
    input: Any?,
    options: ScheduleTaskOptions = ScheduleTaskOptions.DEFAULT
): T = schedule(taskType, input, T::class, options)

/**
 * Schedule a task for execution with maxRetries parameter.
 */
suspend inline fun <reified T : Any> WorkflowContext.schedule(
    taskType: String,
    input: Any?,
    maxRetries: Int
): T = schedule(taskType, input, T::class, ScheduleTaskOptions(maxRetries = maxRetries))

/**
 * Schedule a task for async execution with reified type parameter.
 */
suspend inline fun <reified T : Any> WorkflowContext.scheduleAsync(
    taskType: String,
    input: Any?
): Deferred<T> = scheduleAsync(taskType, input, T::class)
