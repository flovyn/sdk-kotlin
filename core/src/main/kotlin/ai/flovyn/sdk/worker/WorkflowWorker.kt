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

        val workflowExecutionId = UUID.fromString(activation.workflowExecutionId)

        // Build initial state from activation
        val initialState = activation.state.associate { it.key to it.value }

        // Create context
        val context = WorkflowContextImpl(
            workflowExecutionId = workflowExecutionId,
            tenantId = UUID.randomUUID(), // TODO: Get from activation
            input = serializer.deserializeToMap(getInitializeInput(activation.jobs)),
            timestampMs = activation.timestampMs,
            randomSeed = activation.randomSeed,
            initialState = initialState,
            serializer = serializer,
            isReplaying = activation.isReplaying
        )

        // Process cancellation job if present
        if (hasCancellationJob(activation.jobs)) {
            context.requestCancellation()
        }

        try {
            // Notify hook
            hook?.onWorkflowStarted(
                workflowExecutionId,
                activation.workflowKind,
                context.input
            )

            // Execute workflow
            val result = executeWorkflow(workflow, context)

            // Complete workflow
            val commands = context.getCommands() + FfiWorkflowCommand.CompleteWorkflow(
                output = serializer.serialize(result)
            )

            val completion = WorkflowActivationCompletion(
                runId = activation.runId,
                commands = commands
            )

            coreBridge.completeWorkflowActivation(completion)

            hook?.onWorkflowCompleted(workflowExecutionId, activation.workflowKind, result)

        } catch (e: WorkflowSuspendedException) {
            // Workflow needs to suspend - just return the commands
            val completion = WorkflowActivationCompletion(
                runId = activation.runId,
                commands = context.getCommands()
            )
            coreBridge.completeWorkflowActivation(completion)

        } catch (e: WorkflowCancelledException) {
            // Workflow was cancelled
            val commands = context.getCommands() + FfiWorkflowCommand.CancelWorkflow(
                reason = e.message ?: "Workflow cancelled"
            )
            val completion = WorkflowActivationCompletion(
                runId = activation.runId,
                commands = commands
            )
            coreBridge.completeWorkflowActivation(completion)

        } catch (e: Exception) {
            hook?.onWorkflowFailed(workflowExecutionId, activation.workflowKind, e)
            failActivation(activation, e.message ?: "Unknown error", e.stackTraceToString())
        }
    }

    private suspend fun failActivation(
        activation: WorkflowActivation,
        error: String,
        stackTrace: String = ""
    ) {
        val completion = WorkflowActivationCompletion(
            runId = activation.runId,
            commands = listOf(
                FfiWorkflowCommand.FailWorkflow(
                    error = error,
                    stackTrace = stackTrace,
                    failureType = "WorkflowError"
                )
            )
        )
        coreBridge.completeWorkflowActivation(completion)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeWorkflow(
        registered: RegisteredWorkflow<*, *>,
        context: WorkflowContextImpl
    ): Any? {
        val workflow = registered.definition as WorkflowDefinition<Any?, Any?>
        val input = if (registered.inputType == Map::class.java) {
            context.input
        } else {
            serializer.deserialize(
                serializer.serialize(context.input),
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
