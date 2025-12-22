package ai.flovyn.sdk.task

import ai.flovyn.sdk.serialization.JsonSerializer

/**
 * Implementation of TaskContext.
 */
internal class TaskContextImpl(
    override val taskExecutionId: String,
    override val inputBytes: ByteArray,
    override val attempt: Int,
    override val maxRetries: Int,
    private val serializer: JsonSerializer,
    private val initialState: Map<String, ByteArray> = emptyMap(),
    private val progressCallback: suspend (Double, String?) -> Unit = { _, _ -> },
    private val heartbeatCallback: suspend () -> Unit = {},
    private val logCallback: suspend (String, String) -> Unit = { _, _ -> },
    private val streamCallback: suspend (StreamEvent) -> Unit = {},
    private val stateUpdateCallback: suspend (String, ByteArray?) -> Unit = { _, _ -> }
) : TaskContext {

    private var cancelled = false
    private val currentState = initialState.toMutableMap()

    override suspend fun reportProgress(progress: Double, details: String?) {
        require(progress in 0.0..1.0) { "Progress must be between 0.0 and 1.0" }
        progressCallback(progress, details)
    }

    override suspend fun heartbeat() {
        heartbeatCallback()
    }

    override suspend fun log(level: String, message: String) {
        logCallback(level, message)
    }

    override fun isCancelled(): Boolean = cancelled

    override fun checkCancellation() {
        if (cancelled) {
            throw TaskCancelledException()
        }
    }

    override suspend fun stream(event: StreamEvent) {
        streamCallback(event)
    }

    override suspend fun <T> get(key: String): T? {
        val bytes = currentState[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return serializer.deserialize(bytes, Any::class.java) as T?
    }

    override suspend fun <T> set(key: String, value: T) {
        val bytes = serializer.serialize(value)
        currentState[key] = bytes
        stateUpdateCallback(key, bytes)
    }

    override suspend fun clear(key: String) {
        currentState.remove(key)
        stateUpdateCallback(key, null)
    }

    override suspend fun stateKeys(): Set<String> {
        return currentState.keys.toSet()
    }

    /**
     * Mark the task as cancelled.
     */
    internal fun requestCancellation() {
        cancelled = true
    }
}
