package ai.flovyn.sdk.worker

import ai.flovyn.sdk.task.DynamicTaskDefinition
import ai.flovyn.sdk.task.TaskDefinition

/**
 * Registry for task definitions.
 *
 * Manages task registration and lookup by kind.
 */
class TaskRegistry {
    private val tasks = mutableMapOf<String, RegisteredTask<*, *>>()

    /**
     * Register a typed task definition.
     *
     * @param task The task definition to register
     * @param inputType The input type class
     * @param outputType The output type class
     */
    fun <INPUT, OUTPUT> register(
        task: TaskDefinition<INPUT, OUTPUT>,
        inputType: Class<*>,
        outputType: Class<*>
    ) {
        tasks[task.kind] = RegisteredTask(
            definition = task,
            inputType = inputType,
            outputType = outputType
        )
    }

    /**
     * Register a dynamic task (Map-based input/output).
     */
    fun registerDynamic(task: DynamicTaskDefinition) {
        tasks[task.kind] = RegisteredTask(
            definition = task,
            inputType = Map::class.java,
            outputType = Map::class.java
        )
    }

    /**
     * Get a registered task by kind.
     */
    fun get(kind: String): RegisteredTask<*, *>? = tasks[kind]

    /**
     * Check if a task is registered.
     */
    fun has(kind: String): Boolean = kind in tasks

    /**
     * Get all registered task kinds.
     */
    fun getAllKinds(): Set<String> = tasks.keys.toSet()

    /**
     * Get all registered tasks.
     */
    fun getAll(): Collection<RegisteredTask<*, *>> = tasks.values
}

/**
 * A registered task with type information.
 */
data class RegisteredTask<INPUT, OUTPUT>(
    val definition: TaskDefinition<INPUT, OUTPUT>,
    val inputType: Class<*>,
    val outputType: Class<*>
) {
    val kind: String get() = definition.kind
    val name: String get() = definition.name
    val version: String get() = definition.version.toString()
}

/**
 * Inline extension for registering typed tasks with reified generics.
 */
inline fun <reified INPUT, reified OUTPUT> TaskRegistry.register(
    task: TaskDefinition<INPUT, OUTPUT>
) = register(task, INPUT::class.java, OUTPUT::class.java)
