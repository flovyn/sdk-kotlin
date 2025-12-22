package ai.flovyn.sdk.task

/**
 * Context for task execution.
 *
 * Provides methods for progress reporting, heartbeats, logging,
 * cancellation, and state management during task execution.
 */
interface TaskContext {
    /** Unique identifier for this task execution */
    val taskExecutionId: String

    /** The task input (raw bytes, must be deserialized by the task) */
    val inputBytes: ByteArray

    /** Current attempt number (1-indexed) */
    val attempt: Int

    /** Maximum number of retries allowed */
    val maxRetries: Int

    // --- Progress and Monitoring ---

    /**
     * Report progress of the task.
     *
     * @param progress Progress value between 0.0 and 1.0
     * @param details Optional progress details/message
     */
    suspend fun reportProgress(progress: Double, details: String? = null)

    /**
     * Send a heartbeat to indicate the task is still alive.
     * This is called automatically, but can be called manually for long-running operations.
     */
    suspend fun heartbeat()

    /**
     * Log a message from the task.
     *
     * @param level Log level (DEBUG, INFO, WARN, ERROR)
     * @param message The log message
     */
    suspend fun log(level: String, message: String)

    // --- Convenience logging methods ---

    suspend fun logDebug(message: String) = log("DEBUG", message)
    suspend fun logInfo(message: String) = log("INFO", message)
    suspend fun logWarn(message: String) = log("WARN", message)
    suspend fun logError(message: String) = log("ERROR", message)

    // --- Cancellation ---

    /**
     * Check if cancellation has been requested.
     */
    fun isCancelled(): Boolean

    /**
     * Throw TaskCancelledException if cancellation has been requested.
     * Call this at safe points in the task to handle cancellation.
     */
    fun checkCancellation()

    // --- Streaming (ephemeral events) ---

    /**
     * Stream an event to the client.
     * Streaming events are ephemeral and not persisted.
     *
     * @param event The stream event to send
     */
    suspend fun stream(event: StreamEvent)

    // --- State Management (for idempotency) ---

    /**
     * Get a value from task state.
     * Task state is persisted and survives retries.
     *
     * @param key The state key
     * @return The value, or null if not found
     */
    suspend fun <T> get(key: String): T?

    /**
     * Set a value in task state.
     * Task state is persisted and survives retries.
     *
     * @param key The state key
     * @param value The value to store
     */
    suspend fun <T> set(key: String, value: T)

    /**
     * Clear a value from task state.
     *
     * @param key The state key to clear
     */
    suspend fun clear(key: String)

    /**
     * Get all state keys.
     */
    suspend fun stateKeys(): Set<String>
}

/**
 * Stream events that can be sent during task execution.
 */
sealed class StreamEvent {
    /** Token event (e.g., for LLM streaming) */
    data class Token(val text: String) : StreamEvent()

    /** Progress update event */
    data class Progress(val progress: Double, val details: String? = null) : StreamEvent()

    /** Data event (generic JSON data) */
    data class Data(val data: String) : StreamEvent()

    /** Error event */
    data class Error(val message: String, val code: String? = null) : StreamEvent()
}

/**
 * Exception thrown when a task is cancelled.
 */
class TaskCancelledException(message: String = "Task was cancelled") : Exception(message)
