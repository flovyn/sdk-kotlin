package ai.flovyn.sdk.worker

import ai.flovyn.sdk.workflow.DynamicWorkflowDefinition
import ai.flovyn.sdk.workflow.WorkflowDefinition

/**
 * Registry for workflow definitions.
 *
 * Manages workflow registration and lookup by kind.
 */
class WorkflowRegistry {
    private val workflows = mutableMapOf<String, RegisteredWorkflow<*, *>>()

    /**
     * Register a typed workflow definition.
     *
     * @param workflow The workflow definition to register
     * @param inputType The input type class
     * @param outputType The output type class
     */
    fun <INPUT, OUTPUT> register(
        workflow: WorkflowDefinition<INPUT, OUTPUT>,
        inputType: Class<*>,
        outputType: Class<*>
    ) {
        workflows[workflow.kind] = RegisteredWorkflow(
            definition = workflow,
            inputType = inputType,
            outputType = outputType
        )
    }

    /**
     * Register a dynamic workflow (Map-based input/output).
     */
    fun registerDynamic(workflow: DynamicWorkflowDefinition) {
        workflows[workflow.kind] = RegisteredWorkflow(
            definition = workflow,
            inputType = Map::class.java,
            outputType = Map::class.java
        )
    }

    /**
     * Get a registered workflow by kind.
     */
    fun get(kind: String): RegisteredWorkflow<*, *>? = workflows[kind]

    /**
     * Check if a workflow is registered.
     */
    fun has(kind: String): Boolean = kind in workflows

    /**
     * Get all registered workflow kinds.
     */
    fun getAllKinds(): Set<String> = workflows.keys.toSet()

    /**
     * Get all registered workflows.
     */
    fun getAll(): Collection<RegisteredWorkflow<*, *>> = workflows.values
}

/**
 * A registered workflow with type information.
 */
data class RegisteredWorkflow<INPUT, OUTPUT>(
    val definition: WorkflowDefinition<INPUT, OUTPUT>,
    val inputType: Class<*>,
    val outputType: Class<*>
) {
    val kind: String get() = definition.kind
    val name: String get() = definition.name
    val version: String get() = definition.version.toString()
}

/**
 * Inline extension for registering typed workflows with reified generics.
 */
inline fun <reified INPUT, reified OUTPUT> WorkflowRegistry.register(
    workflow: WorkflowDefinition<INPUT, OUTPUT>
) = register(workflow, INPUT::class.java, OUTPUT::class.java)
