package ai.flovyn.sdk.client

import java.util.UUID

/**
 * Interface for workflow lifecycle hooks.
 *
 * Implement this interface to receive notifications about workflow execution events.
 * Useful for logging, metrics, monitoring, and other observability needs.
 *
 * Example:
 * ```kotlin
 * class MetricsHook : WorkflowHook {
 *     override suspend fun onWorkflowStarted(workflowExecutionId: UUID, workflowKind: String, input: Map<String, Any?>) {
 *         metrics.increment("workflow.started", tags = mapOf("kind" to workflowKind))
 *     }
 *
 *     override suspend fun onWorkflowCompleted(workflowExecutionId: UUID, workflowKind: String, result: Any?) {
 *         metrics.increment("workflow.completed", tags = mapOf("kind" to workflowKind))
 *     }
 * }
 * ```
 */
interface WorkflowHook {
    /**
     * Called when a workflow execution starts.
     *
     * @param workflowExecutionId The workflow execution ID
     * @param workflowKind The kind of workflow
     * @param input The workflow input
     */
    suspend fun onWorkflowStarted(
        workflowExecutionId: UUID,
        workflowKind: String,
        input: Map<String, Any?>
    ) {}

    /**
     * Called when a workflow execution completes successfully.
     *
     * @param workflowExecutionId The workflow execution ID
     * @param workflowKind The kind of workflow
     * @param result The workflow result
     */
    suspend fun onWorkflowCompleted(
        workflowExecutionId: UUID,
        workflowKind: String,
        result: Any?
    ) {}

    /**
     * Called when a workflow execution fails.
     *
     * @param workflowExecutionId The workflow execution ID
     * @param workflowKind The kind of workflow
     * @param error The exception that caused the failure
     */
    suspend fun onWorkflowFailed(
        workflowExecutionId: UUID,
        workflowKind: String,
        error: Throwable
    ) {}

    /**
     * Called when a task is scheduled from a workflow.
     *
     * @param workflowExecutionId The workflow execution ID
     * @param taskId The task execution ID
     * @param taskType The type of task
     * @param input The task input
     */
    suspend fun onTaskScheduled(
        workflowExecutionId: UUID,
        taskId: String,
        taskType: String,
        input: Any?
    ) {}
}

/**
 * Composite hook that delegates to multiple hooks.
 */
internal class CompositeWorkflowHook(
    private val hooks: List<WorkflowHook>
) : WorkflowHook {

    override suspend fun onWorkflowStarted(
        workflowExecutionId: UUID,
        workflowKind: String,
        input: Map<String, Any?>
    ) {
        hooks.forEach { hook ->
            runCatching { hook.onWorkflowStarted(workflowExecutionId, workflowKind, input) }
        }
    }

    override suspend fun onWorkflowCompleted(
        workflowExecutionId: UUID,
        workflowKind: String,
        result: Any?
    ) {
        hooks.forEach { hook ->
            runCatching { hook.onWorkflowCompleted(workflowExecutionId, workflowKind, result) }
        }
    }

    override suspend fun onWorkflowFailed(
        workflowExecutionId: UUID,
        workflowKind: String,
        error: Throwable
    ) {
        hooks.forEach { hook ->
            runCatching { hook.onWorkflowFailed(workflowExecutionId, workflowKind, error) }
        }
    }

    override suspend fun onTaskScheduled(
        workflowExecutionId: UUID,
        taskId: String,
        taskType: String,
        input: Any?
    ) {
        hooks.forEach { hook ->
            runCatching { hook.onTaskScheduled(workflowExecutionId, taskId, taskType, input) }
        }
    }
}
