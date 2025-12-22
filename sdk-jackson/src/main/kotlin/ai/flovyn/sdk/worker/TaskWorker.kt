package ai.flovyn.sdk.worker

import ai.flovyn.core.CoreBridge
import ai.flovyn.sdk.serialization.JsonSerializer
import ai.flovyn.sdk.task.*
import uniffi.flovyn_ffi.*

/**
 * Worker that processes task activations.
 */
internal class TaskWorker(
    private val coreBridge: CoreBridge,
    private val registry: TaskRegistry,
    private val serializer: JsonSerializer
) {
    /**
     * Run the task worker loop.
     */
    suspend fun run() {
        while (!coreBridge.isShutdownRequested()) {
            try {
                val activation = coreBridge.pollTaskActivation() ?: continue
                processActivation(activation)
            } catch (e: Exception) {
                if (coreBridge.isShutdownRequested()) break
                // Log error and continue
                e.printStackTrace()
            }
        }
    }

    private suspend fun processActivation(activation: TaskActivation) {
        val task = registry.get(activation.taskKind)
            ?: return failTask(activation, "Unknown task: ${activation.taskKind}")

        // Create context
        val context = TaskContextImpl(
            taskExecutionId = activation.taskExecutionId,
            inputBytes = activation.input,
            attempt = activation.attempt.toInt(),
            maxRetries = activation.maxRetries.toInt(),
            serializer = serializer
        )

        try {
            val result = executeTask(task, context, activation.input)

            val completion = TaskCompletion.Completed(
                taskExecutionId = activation.taskExecutionId,
                output = serializer.serialize(result)
            )

            coreBridge.completeTask(completion)

        } catch (e: TaskCancelledException) {
            val completion = TaskCompletion.Cancelled(
                taskExecutionId = activation.taskExecutionId
            )
            coreBridge.completeTask(completion)

        } catch (e: Exception) {
            failTask(activation, e.message ?: "Unknown error", isRetryable(e, task.definition))
        }
    }

    private fun failTask(
        activation: TaskActivation,
        error: String,
        retryable: Boolean = true
    ) {
        val completion = TaskCompletion.Failed(
            taskExecutionId = activation.taskExecutionId,
            error = error,
            retryable = retryable
        )
        coreBridge.completeTask(completion)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeTask(
        registered: RegisteredTask<*, *>,
        context: TaskContextImpl,
        inputBytes: ByteArray
    ): Any? {
        val task = registered.definition as TaskDefinition<Any?, Any?>
        val input = if (registered.inputType == Map::class.java) {
            serializer.deserializeToMap(inputBytes)
        } else {
            serializer.deserialize(inputBytes, registered.inputType)
        }
        return task.execute(input, context)
    }

    private fun isRetryable(e: Exception, task: TaskDefinition<*, *>): Boolean {
        // By default, all exceptions are retryable
        // Specific non-retryable exceptions can be marked
        return true
    }
}
