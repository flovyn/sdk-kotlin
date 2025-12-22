package ai.flovyn.sdk.workflow

/**
 * A durable promise that can be resolved externally.
 *
 * Durable promises are persisted and survive workflow restarts.
 * They can be resolved or rejected by external systems via the Flovyn API.
 */
interface DurablePromise<T> {
    /** The unique name of this promise */
    val name: String

    /**
     * Await the resolution of this promise.
     * Suspends until the promise is resolved, rejected, or times out.
     *
     * @throws PromiseRejectedException if the promise was rejected
     * @throws PromiseTimeoutException if the promise timed out
     */
    suspend fun await(): T

    /**
     * Check if the promise has been resolved or rejected.
     */
    fun isCompleted(): Boolean
}

/**
 * Exception thrown when a promise is rejected.
 */
class PromiseRejectedException(
    val promiseName: String,
    val reason: String
) : Exception("Promise '$promiseName' was rejected: $reason")

/**
 * Exception thrown when a promise times out.
 */
class PromiseTimeoutException(
    val promiseName: String
) : Exception("Promise '$promiseName' timed out")
