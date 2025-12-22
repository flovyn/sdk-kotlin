package ai.flovyn.sdk.workflow

import ai.flovyn.sdk.common.SemanticVersion

/**
 * Type-safe workflow definition with generic INPUT and OUTPUT types.
 *
 * This is the single, unified way to define workflows. For dynamic/untyped
 * workflows, use WorkflowDefinition<Map<String, Any?>, Map<String, Any?>>
 * or the [DynamicWorkflowDefinition] type alias.
 *
 * Example (typed):
 * ```kotlin
 * data class OrderInput(val orderId: String, val items: List<String>)
 * data class OrderOutput(val orderId: String, val status: String)
 *
 * class OrderWorkflow : WorkflowDefinition<OrderInput, OrderOutput>() {
 *     override val kind = "order-processing"
 *     override val name = "Order Processing"
 *     override val version = SemanticVersion(2, 0, 0)
 *     override val cancellable = true
 *
 *     override suspend fun execute(ctx: WorkflowContext, input: OrderInput): OrderOutput {
 *         val payment = ctx.schedule<PaymentResult>("payment-task", input.paymentDetails)
 *         return OrderOutput(orderId = input.orderId, status = "completed")
 *     }
 * }
 * ```
 *
 * @param INPUT the input type for the workflow (must be serializable)
 * @param OUTPUT the output type for the workflow (must be serializable)
 */
abstract class WorkflowDefinition<INPUT, OUTPUT> {
    // --- Required: Must be overridden ---

    /**
     * Unique identifier for this workflow type.
     * Used for routing, monitoring, and registration. Should be kebab-case.
     */
    abstract val kind: String

    /**
     * Execute the workflow logic.
     */
    abstract suspend fun execute(ctx: WorkflowContext, input: INPUT): OUTPUT

    // --- Optional: Have defaults, can be overridden ---

    /**
     * Human-readable name for display in UI.
     * Defaults to class name if not specified.
     */
    open val name: String get() = this::class.simpleName ?: kind

    /**
     * Semantic version for version tracking.
     * Enforces MAJOR.MINOR.PATCH format.
     */
    open val version: SemanticVersion get() = SemanticVersion.DEFAULT

    /**
     * Description of what this workflow does.
     */
    open val description: String? get() = null

    /**
     * Timeout in seconds. Null means no timeout (server default applies).
     */
    open val timeoutSeconds: Int? get() = null

    /**
     * Whether this workflow supports cooperative cancellation via ctx.checkCancellation().
     * Set to true only if the workflow explicitly checks for cancellation and handles cleanup.
     */
    open val cancellable: Boolean get() = false

    /**
     * Tags for categorization and filtering.
     */
    open val tags: List<String> get() = emptyList()
}

/**
 * Type alias for dynamic/untyped workflows (e.g., visual workflows).
 */
typealias DynamicWorkflowDefinition = WorkflowDefinition<Map<String, Any?>, Map<String, Any?>>
