package ai.flovyn.sdk.task

/**
 * Options for scheduling a task from a workflow.
 */
data class ScheduleTaskOptions(
    /**
     * Maximum number of retry attempts.
     * Defaults to the task's configured retry policy.
     */
    val maxRetries: Int? = null,

    /**
     * Timeout in seconds for this specific invocation.
     * Defaults to the task's configured timeout.
     */
    val timeoutSeconds: Int? = null,

    /**
     * Optional idempotency key.
     * If provided, ensures the task executes at most once for this key.
     */
    val idempotencyKey: String? = null,

    /**
     * Priority offset in seconds.
     * Higher values = lower priority (executed later).
     */
    val prioritySeconds: Int = 0
) {
    companion object {
        val DEFAULT = ScheduleTaskOptions()
    }
}
