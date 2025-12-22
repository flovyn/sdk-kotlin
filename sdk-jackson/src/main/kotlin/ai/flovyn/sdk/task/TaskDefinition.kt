package ai.flovyn.sdk.task

import ai.flovyn.sdk.common.SemanticVersion

/**
 * Base class for all task definitions.
 *
 * Tasks are long-running or specialized operations invoked from workflows via ctx.schedule().
 *
 * Example:
 * ```kotlin
 * data class PaymentInput(val orderId: String, val amount: Double)
 * data class PaymentOutput(val transactionId: String, val status: String)
 *
 * class PaymentTask : TaskDefinition<PaymentInput, PaymentOutput>() {
 *     override val kind = "payment-task"
 *     override val name = "Process Payment"
 *     override val description = "Processes payment via external gateway"
 *     override val timeoutSeconds = 300
 *     override val cancellable = true
 *     override val tags = listOf("payment", "external-api")
 *
 *     override suspend fun execute(input: PaymentInput, context: TaskContext): PaymentOutput {
 *         context.reportProgress(0.1, "Initiating payment...")
 *         // Process payment
 *         return PaymentOutput(transactionId = "txn_123", status = "success")
 *     }
 * }
 * ```
 *
 * @param INPUT the input type for the task (must be serializable)
 * @param OUTPUT the output type for the task (must be serializable)
 */
abstract class TaskDefinition<INPUT, OUTPUT> {
    // --- Required ---

    /**
     * Unique identifier for this task type.
     * Used for routing and invocation via ctx.schedule(taskType = "...").
     */
    abstract val kind: String

    /**
     * Execute the task logic.
     */
    abstract suspend fun execute(input: INPUT, context: TaskContext): OUTPUT

    // --- Optional with defaults ---

    /**
     * Human-readable name for display in UI.
     */
    open val name: String get() = this::class.simpleName ?: kind

    /**
     * Semantic version for version tracking.
     */
    open val version: SemanticVersion get() = SemanticVersion.DEFAULT

    /**
     * Description of what this task does.
     */
    open val description: String? get() = null

    /**
     * Timeout in seconds. Null means server default.
     */
    open val timeoutSeconds: Int? get() = null

    /**
     * Whether this task supports cancellation via context.isCancelled().
     */
    open val cancellable: Boolean get() = false

    /**
     * Tags for categorization and filtering.
     */
    open val tags: List<String> get() = emptyList()

    /**
     * Retry configuration for this task.
     */
    open val retryConfig: RetryConfig get() = RetryConfig.DEFAULT

    /**
     * Heartbeat timeout in seconds. Null means server default (5 minutes).
     * Heartbeats are sent at half this interval during task execution.
     */
    open val heartbeatTimeoutSeconds: Int? get() = null
}

/**
 * Retry configuration for tasks.
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 60000,
    val backoffMultiplier: Double = 2.0
) {
    companion object {
        val DEFAULT = RetryConfig()
        val NO_RETRY = RetryConfig(maxAttempts = 1)
    }
}

/**
 * Convenience alias for tasks with untyped Map<String, Any?> inputs/outputs.
 * Useful for dynamic/generic task handlers.
 */
typealias DynamicTaskDefinition = TaskDefinition<Map<String, Any?>, Map<String, Any?>>
