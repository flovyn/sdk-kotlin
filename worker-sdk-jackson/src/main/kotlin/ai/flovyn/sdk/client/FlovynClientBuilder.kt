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
import uniffi.flovyn_worker_ffi.OAuth2Credentials
import java.util.UUID

/**
 * Builder for creating FlovynClient instances with Jackson serialization.
 *
 * Example:
 * ```kotlin
 * val client = FlovynClientBuilder()
 *     .serverAddress("localhost", 9090)
 *     .orgId(orgId)
 *     .queue("default")
 *     .registerWorkflow(MyWorkflow())
 *     .registerTask(MyTask())
 *     .build()
 * ```
 */
class FlovynClientBuilder {
    private var serverHost: String = "localhost"
    private var serverPort: Int = 9090
    private var orgId: UUID? = null
    private var workerToken: String? = null
    private var oauth2Credentials: OAuth2Credentials? = null
    private var workerId: String = "worker-${UUID.randomUUID()}"
    private var queue: String = "default"
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
     * Set the org ID.
     */
    fun orgId(id: UUID) = apply {
        this.orgId = id
    }

    /**
     * Set the worker token for authentication.
     * Worker tokens are obtained from the Flovyn server.
     */
    fun workerToken(token: String) = apply {
        this.workerToken = token
    }

    /**
     * Set OAuth2 client credentials for authentication.
     *
     * The SDK will fetch a JWT from the OAuth2 provider using the client credentials
     * flow and use it for all gRPC requests.
     *
     * @param clientId OAuth2 client ID
     * @param clientSecret OAuth2 client secret
     * @param tokenEndpoint Token endpoint URL (e.g., "https://keycloak.example.com/realms/myrealm/protocol/openid-connect/token")
     * @param scopes Optional scopes (space-separated)
     */
    fun oauth2ClientCredentials(
        clientId: String,
        clientSecret: String,
        tokenEndpoint: String,
        scopes: String? = null
    ) = apply {
        this.oauth2Credentials = OAuth2Credentials(
            clientId = clientId,
            clientSecret = clientSecret,
            tokenEndpoint = tokenEndpoint,
            scopes = scopes
        )
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
    fun queue(queue: String) = apply {
        this.queue = queue
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
        // Either workerToken or orgId must be provided
        require(workerToken != null || orgId != null) {
            "Either workerToken or orgId is required"
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
            oauth2Credentials = oauth2Credentials,
            orgId = orgId,
            workerId = workerId,
            queue = queue,
            maxConcurrentWorkflows = maxConcurrentWorkflows,
            maxConcurrentTasks = maxConcurrentTasks,
            workflowRegistry = workflowRegistry,
            taskRegistry = taskRegistry,
            workflowHook = compositeHook,
            serializer = serializer
        )
    }
}
