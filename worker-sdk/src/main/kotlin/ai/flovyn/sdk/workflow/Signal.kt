package ai.flovyn.sdk.workflow

/**
 * A signal received by a workflow.
 *
 * Signals are used for external communication with running workflows.
 * They are persisted and survive workflow restarts.
 *
 * @param T The type of the signal value
 */
data class Signal<T>(
    /** The name of the signal */
    val name: String,
    /** The signal payload value */
    val value: T,
)
