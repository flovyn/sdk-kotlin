package ai.flovyn.sdk.workflow

/**
 * A deferred result that can be awaited.
 *
 * Similar to Kotlin's Deferred, but specifically for workflow operations
 * that need to be tracked for replay.
 */
interface Deferred<T> {
    /**
     * Await the result of this deferred operation.
     * Suspends until the result is available.
     */
    suspend fun await(): T

    /**
     * Check if the result is already available.
     */
    fun isCompleted(): Boolean
}
