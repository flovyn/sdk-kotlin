package ai.flovyn.core

import ai.flovyn.native.NativeLoader
import uniffi.flovyn_ffi.*

/**
 * Bridge to the Flovyn FFI core library.
 *
 * This class wraps the uniffi-generated bindings and provides a Kotlin-native API
 * for interacting with the Rust core.
 */
class CoreBridge private constructor(
    private val ffiWorker: CoreWorker
) : AutoCloseable {

    /**
     * Register the worker with the server.
     * Returns the server-assigned worker ID.
     */
    fun register(): String {
        return ffiWorker.register()
    }

    /**
     * Poll for the next workflow activation.
     * Returns null if no work is available or shutdown was requested.
     */
    fun pollWorkflowActivation(): WorkflowActivation? {
        return ffiWorker.pollWorkflowActivation()
    }

    /**
     * Complete a workflow activation with the given completion.
     */
    fun completeWorkflowActivation(completion: WorkflowActivationCompletion) {
        ffiWorker.completeWorkflowActivation(completion)
    }

    /**
     * Poll for the next task activation.
     * Returns null if no work is available or shutdown was requested.
     */
    fun pollTaskActivation(): TaskActivation? {
        return ffiWorker.pollTaskActivation()
    }

    /**
     * Complete a task with the given completion.
     */
    fun completeTask(completion: TaskCompletion) {
        ffiWorker.completeTask(completion)
    }

    /**
     * Initiate graceful shutdown of the worker.
     */
    fun initiateShutdown() {
        ffiWorker.initiateShutdown()
    }

    /**
     * Check if shutdown has been requested.
     */
    fun isShutdownRequested(): Boolean {
        return ffiWorker.isShutdownRequested()
    }

    /**
     * Get the current worker status as a string.
     */
    fun getStatus(): String {
        return ffiWorker.getStatus()
    }

    override fun close() {
        ffiWorker.close()
    }

    companion object {
        init {
            // Ensure native library is loaded before any uniffi access
            NativeLoader.ensureLoaded()
        }

        /**
         * Create a new CoreBridge with the given configuration.
         */
        fun create(config: WorkerConfig): CoreBridge {
            val ffiWorker = CoreWorker(config)
            return CoreBridge(ffiWorker)
        }
    }
}
