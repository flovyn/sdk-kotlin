package ai.flovyn.sdk.worker

import ai.flovyn.core.CoreBridge
import ai.flovyn.sdk.client.WorkflowHook
import ai.flovyn.sdk.serialization.JsonSerializer
import ai.flovyn.sdk.workflow.*
import uniffi.flovyn_ffi.*
import java.util.UUID

/**
 * Worker that processes workflow activations.
 */
internal class WorkflowWorker(
    private val coreBridge: CoreBridge,
    private val registry: WorkflowRegistry,
    private val hook: WorkflowHook?,
    private val serializer: JsonSerializer
) {
    /**
     * Run the workflow worker loop.
     */
    suspend fun run() {
        while (!coreBridge.isShutdownRequested()) {
            try {
                val activation = coreBridge.pollWorkflowActivation() ?: continue
                processActivation(activation)
            } catch (e: Exception) {
                if (coreBridge.isShutdownRequested()) break
                // Log error and continue
                e.printStackTrace()
            }
        }
    }

    private suspend fun processActivation(activation: WorkflowActivation) {
        val workflow = registry.get(activation.workflowKind)
            ?: return failActivation(activation, "Unknown workflow: ${activation.workflowKind}")

        val ffiContext = activation.context

        // Create Kotlin context wrapping FFI context
        val context = WorkflowContextImpl(ffiContext, serializer)

        // Process cancellation job if present
        if (hasCancellationJob(activation.jobs)) {
            context.requestCancellation()
        }

        try {
            // Notify hook
            val workflowExecutionId = UUID.fromString(ffiContext.workflowExecutionId())
            val inputMap = serializer.deserializeToMap(getInitializeInput(activation.jobs))

            hook?.onWorkflowStarted(
                workflowExecutionId,
                activation.workflowKind,
                inputMap
            )

            // Execute workflow
            val result = executeWorkflow(workflow, context, inputMap)

            // Complete workflow - FFI context has accumulated commands
            coreBridge.completeWorkflowActivation(
                ffiContext,
                WorkflowCompletionStatus.Completed(serializer.serialize(result))
            )

            hook?.onWorkflowCompleted(workflowExecutionId, activation.workflowKind, result)

        } catch (e: WorkflowSuspendedException) {
            // Workflow needs to suspend - FFI context has accumulated commands
            coreBridge.completeWorkflowActivation(
                ffiContext,
                WorkflowCompletionStatus.Suspended
            )

        } catch (e: WorkflowCancelledException) {
            // Workflow was cancelled
            coreBridge.completeWorkflowActivation(
                ffiContext,
                WorkflowCompletionStatus.Cancelled(e.message ?: "Workflow cancelled")
            )

        } catch (e: DeterminismViolationException) {
            // Determinism violation detected by FFI context
            val workflowExecutionId = UUID.fromString(ffiContext.workflowExecutionId())
            hook?.onWorkflowFailed(workflowExecutionId, activation.workflowKind, e)
            coreBridge.completeWorkflowActivation(
                ffiContext,
                WorkflowCompletionStatus.Failed("Determinism violation: ${e.message}")
            )

        } catch (e: Exception) {
            val workflowExecutionId = UUID.fromString(ffiContext.workflowExecutionId())
            hook?.onWorkflowFailed(workflowExecutionId, activation.workflowKind, e)
            coreBridge.completeWorkflowActivation(
                ffiContext,
                WorkflowCompletionStatus.Failed(e.message ?: "Unknown error")
            )
        }
    }

    private fun failActivation(
        activation: WorkflowActivation,
        error: String
    ) {
        coreBridge.completeWorkflowActivation(
            activation.context,
            WorkflowCompletionStatus.Failed(error)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeWorkflow(
        registered: RegisteredWorkflow<*, *>,
        context: WorkflowContextImpl,
        inputMap: Map<String, Any?>
    ): Any? {
        val workflow = registered.definition as WorkflowDefinition<Any?, Any?>
        val input = if (registered.inputType == Map::class.java) {
            inputMap
        } else {
            serializer.deserialize(
                serializer.serialize(inputMap),
                registered.inputType
            )
        }
        return workflow.execute(context, input)
    }

    private fun getInitializeInput(jobs: List<WorkflowActivationJob>): ByteArray {
        for (job in jobs) {
            if (job is WorkflowActivationJob.Initialize) {
                return job.input
            }
        }
        return byteArrayOf()
    }

    private fun hasCancellationJob(jobs: List<WorkflowActivationJob>): Boolean {
        return jobs.any { it is WorkflowActivationJob.CancelWorkflow }
    }
}
