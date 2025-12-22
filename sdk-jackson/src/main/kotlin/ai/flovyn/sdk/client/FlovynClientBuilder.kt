package ai.flovyn.sdk.client

import ai.flovyn.sdk.serialization.JacksonSerializer
import ai.flovyn.sdk.serialization.JsonSerializer
import ai.flovyn.sdk.task.DynamicTaskDefinition
import ai.flovyn.sdk.task.TaskDefinition
import ai.flovyn.sdk.worker.TaskRegistry
import ai.flovyn.sdk.worker.WorkflowRegistry
import ai.flovyn.sdk.worker.register
import ai.flovyn.sdk.workflow.DynamicWorkflowDefinition
import ai.flovyn.sdk.workflow.WorkflowDefinition
import java.util.UUID

/**
 * Builder for creating FlovynClient instances.
 *
 * Example:
 * ```kotlin
 * val client = FlovynClient.builder()
 *     .serverAddress("localhost", 9090)
 *     .tenantId(tenantId)
 *     .taskQueue("default")
 *     .registerWorkflow(MyWorkflow())
 *     .registerTask(MyTask())
 *     .build()
 * ```
 */
class FlovynClientBuilder {
    private var serverHost: String = "localhost"
    private var serverPort: Int = 9090
    private var tenantId: UUID? = null
    private var workerToken: String? = null
    private var workerId: String = "worker-${UUID.randomUUID()}"
    private var taskQueue: String = "default"
    private var maxConcurrentWorkflows: Int = 10
    private var maxConcurrentTasks: Int = 20
    private var pollTimeoutSeconds: Long = 60
    private var serializer: JsonSerializer = JacksonSerializer()

    @PublishedApi
    internal val workflowRegistry = WorkflowRegistry()
    @PublishedApi
    internal val taskRegistry = TaskRegistry()
    private val hooks = mutableListOf<WorkflowHook>()

    /**
     * Set the server address.
     */
    fun serverAddress(host: String, port: Int) = apply {
        this.serverHost = host
        this.serverPort = port
    }

    /**
     * Set the tenant ID.
     */
    fun tenantId(id: UUID) = apply {
        this.tenantId = id
    }

    /**
     * Set the worker token for authentication.
     * Worker tokens are obtained from the Flovyn server.
     */
    fun workerToken(token: String) = apply {
        this.workerToken = token
    }

    /**
     * Set the worker ID.
     */
    fun workerId(id: String) = apply {
        this.workerId = id
    }

    /**
     * Set the task queue that this worker will poll from.
     */
    fun taskQueue(queue: String) = apply {
        this.taskQueue = queue
    }

    /**
     * Set the long-polling timeout in seconds.
     */
    fun pollTimeout(seconds: Long) = apply {
        this.pollTimeoutSeconds = seconds
    }

    /**
     * Set the maximum concurrent workflows.
     */
    fun maxConcurrentWorkflows(max: Int) = apply {
        this.maxConcurrentWorkflows = max
    }

    /**
     * Set the maximum concurrent tasks.
     */
    fun maxConcurrentTasks(max: Int) = apply {
        this.maxConcurrentTasks = max
    }

    /**
     * Set the JSON serializer.
     */
    fun serializer(serializer: JsonSerializer) = apply {
        this.serializer = serializer
    }

    /**
     * Register a typed workflow definition.
     */
    inline fun <reified INPUT, reified OUTPUT> registerWorkflow(
        workflow: WorkflowDefinition<INPUT, OUTPUT>
    ) = apply {
        workflowRegistry.register(workflow)
    }

    /**
     * Register a dynamic workflow (Map-based input/output).
     */
    @JvmName("registerDynamicWorkflow")
    fun registerWorkflow(workflow: DynamicWorkflowDefinition) = apply {
        workflowRegistry.registerDynamic(workflow)
    }

    /**
     * Register a typed task definition.
     */
    inline fun <reified INPUT, reified OUTPUT> registerTask(
        task: TaskDefinition<INPUT, OUTPUT>
    ) = apply {
        taskRegistry.register(task)
    }

    /**
     * Register a dynamic task (Map-based input/output).
     */
    @JvmName("registerDynamicTask")
    fun registerTask(task: DynamicTaskDefinition) = apply {
        taskRegistry.registerDynamic(task)
    }

    /**
     * Register a workflow lifecycle hook.
     */
    fun registerHook(hook: WorkflowHook) = apply {
        hooks.add(hook)
    }

    /**
     * Build the FlovynClient.
     */
    fun build(): FlovynClient {
        // Either workerToken or tenantId must be provided
        require(workerToken != null || tenantId != null) {
            "Either workerToken or tenantId is required"
        }

        val compositeHook = when {
            hooks.isEmpty() -> null
            hooks.size == 1 -> hooks[0]
            else -> CompositeWorkflowHook(hooks)
        }

        return FlovynClient(
            serverHost = serverHost,
            serverPort = serverPort,
            workerToken = workerToken,
            tenantId = tenantId,
            workerId = workerId,
            taskQueue = taskQueue,
            maxConcurrentWorkflows = maxConcurrentWorkflows,
            maxConcurrentTasks = maxConcurrentTasks,
            workflowRegistry = workflowRegistry,
            taskRegistry = taskRegistry,
            workflowHook = compositeHook,
            serializer = serializer
        )
    }
}
