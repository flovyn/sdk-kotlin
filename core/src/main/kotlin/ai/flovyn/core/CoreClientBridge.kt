package ai.flovyn.core

import ai.flovyn.native.NativeLoader
import uniffi.flovyn_ffi.*

/**
 * Bridge to the Flovyn FFI core client library.
 *
 * This class wraps the uniffi-generated CoreClient and provides a Kotlin-native API
 * for client operations like starting workflows.
 */
class CoreClientBridge private constructor(
    private val ffiClient: CoreClient
) : AutoCloseable {

    /**
     * Start a new workflow execution.
     *
     * @param workflowKind The kind of workflow to start
     * @param input The workflow input as JSON bytes
     * @param taskQueue The task queue to use
     * @param workflowVersion Optional workflow version
     * @param idempotencyKey Optional idempotency key
     * @return The response containing the workflow execution ID
     */
    fun startWorkflow(
        workflowKind: String,
        input: ByteArray,
        taskQueue: String,
        workflowVersion: String? = null,
        idempotencyKey: String? = null
    ): StartWorkflowResponse {
        return ffiClient.startWorkflow(
            workflowKind = workflowKind,
            input = input,
            taskQueue = taskQueue,
            workflowVersion = workflowVersion,
            idempotencyKey = idempotencyKey
        )
    }

    /**
     * Get workflow events for a workflow execution.
     *
     * @param workflowExecutionId The workflow execution ID
     * @return List of workflow event records
     */
    fun getWorkflowEvents(workflowExecutionId: String): List<WorkflowEventRecord> {
        return ffiClient.getWorkflowEvents(workflowExecutionId)
    }

    override fun close() {
        ffiClient.close()
    }

    companion object {
        init {
            // Ensure native library is loaded before any uniffi access
            NativeLoader.ensureLoaded()
        }

        /**
         * Create a new CoreClientBridge with the given configuration.
         */
        fun create(config: ClientConfig): CoreClientBridge {
            val ffiClient = CoreClient(config)
            return CoreClientBridge(ffiClient)
        }
    }
}
