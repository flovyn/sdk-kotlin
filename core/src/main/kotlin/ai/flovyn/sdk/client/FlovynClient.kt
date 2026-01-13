package ai.flovyn.sdk.client

import ai.flovyn.core.CoreBridge
import ai.flovyn.core.CoreClientBridge
import ai.flovyn.sdk.serialization.JsonSerializer
import ai.flovyn.sdk.worker.TaskRegistry
import ai.flovyn.sdk.worker.WorkflowRegistry
import ai.flovyn.sdk.worker.WorkflowWorker
import ai.flovyn.sdk.worker.TaskWorker
import kotlinx.coroutines.*
import uniffi.flovyn_ffi.ClientConfig
import uniffi.flovyn_ffi.OAuth2Credentials
import uniffi.flovyn_ffi.TaskMetadataFfi
import uniffi.flovyn_ffi.WorkerConfig
import uniffi.flovyn_ffi.WorkflowMetadataFfi
import java.util.UUID

/**
 * Main entry point for the Flovyn SDK.
 *
 * FlovynClient manages workflow and task workers, providing a unified interface
 * for starting workers and executing workflows.
 *
 * Example:
 * ```kotlin
 * val client = FlovynClientBuilder()
 *     .serverAddress("localhost", 9090)
 *     .orgId(orgId)
 *     .registerWorkflow(MyWorkflow())
 *     .registerTask(MyTask())
 *     .build()
 *
 * client.start()
 * // ... client runs in background
 * client.stop()
 * ```
 */
class FlovynClient(
    private val serverHost: String,
    private val serverPort: Int,
    private val workerToken: String?,
    private val oauth2Credentials: OAuth2Credentials?,
    private val orgId: UUID?,
    private val workerId: String,
    private val queue: String,
    private val maxConcurrentWorkflows: Int,
    private val maxConcurrentTasks: Int,
    internal val workflowRegistry: WorkflowRegistry,
    internal val taskRegistry: TaskRegistry,
    private val workflowHook: WorkflowHook?,
    private val serializer: JsonSerializer
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coreBridge: CoreBridge? = null
    @PublishedApi
    internal var coreClient: CoreClientBridge? = null
    private var workflowWorker: WorkflowWorker? = null
    private var taskWorker: TaskWorker? = null
    private var started = false
    @PublishedApi
    internal val internalSerializer: JsonSerializer = serializer

    /**
     * Check if a workflow is registered.
     */
    fun hasWorkflow(kind: String): Boolean = workflowRegistry.has(kind)

    /**
     * Check if a task is registered.
     */
    fun hasTask(kind: String): Boolean = taskRegistry.has(kind)

    /**
     * Start the client and begin processing workflows/tasks.
     */
    suspend fun start() {
        if (started) {
            throw IllegalStateException("Client already started")
        }

        // gRPC URL format: http://host:port
        val serverUrl = "http://$serverHost:$serverPort"

        // Org ID is required
        val orgIdStr = orgId?.toString()
            ?: throw IllegalStateException("orgId must be set")

        // Convert registered workflows to FFI metadata
        val workflowMetadata = workflowRegistry.getAll().map { registered ->
            val def = registered.definition
            WorkflowMetadataFfi(
                kind = def.kind,
                name = def.name,
                description = def.description,
                version = def.version.toString(),
                tags = def.tags,
                cancellable = def.cancellable,
                timeoutSeconds = def.timeoutSeconds?.toUInt(),
                inputSchema = def.inputSchema,
                outputSchema = def.outputSchema
            )
        }

        // Convert registered tasks to FFI metadata
        val taskMetadata = taskRegistry.getAll().map { registered ->
            val def = registered.definition
            TaskMetadataFfi(
                kind = def.kind,
                name = def.name,
                description = def.description,
                version = def.version.toString(),
                tags = def.tags,
                cancellable = def.cancellable,
                timeoutSeconds = def.timeoutSeconds?.toUInt(),
                inputSchema = null, // Task schema support deferred
                outputSchema = null
            )
        }

        // Create worker configuration
        val workerConfig = WorkerConfig(
            serverUrl = serverUrl,
            workerToken = workerToken,
            oauth2Credentials = oauth2Credentials,
            orgId = orgIdStr,
            queue = queue,
            workerIdentity = workerId,
            maxConcurrentWorkflowTasks = maxConcurrentWorkflows.toUInt(),
            maxConcurrentTasks = maxConcurrentTasks.toUInt(),
            workflowMetadata = workflowMetadata,
            taskMetadata = taskMetadata
        )

        // Create client configuration
        val clientConfig = ClientConfig(
            serverUrl = serverUrl,
            clientToken = null,
            oauth2Credentials = oauth2Credentials,
            orgId = orgIdStr
        )

        // Initialize bridges
        coreBridge = CoreBridge.create(workerConfig)
        coreClient = CoreClientBridge.create(clientConfig)

        // Register with server
        coreBridge!!.register()

        // Create and start workers
        workflowWorker = WorkflowWorker(
            coreBridge = coreBridge!!,
            registry = workflowRegistry,
            hook = workflowHook,
            serializer = serializer
        )

        taskWorker = TaskWorker(
            coreBridge = coreBridge!!,
            registry = taskRegistry,
            serializer = serializer
        )

        // Start worker loops
        scope.launch { workflowWorker!!.run() }
        scope.launch { taskWorker!!.run() }

        started = true
    }

    /**
     * Start a new workflow execution.
     *
     * @param workflowKind The kind of workflow to start
     * @param input The workflow input
     * @param options Optional start workflow options
     * @return The workflow execution ID
     */
    suspend fun startWorkflow(
        workflowKind: String,
        input: Any? = null,
        options: StartWorkflowOptions = StartWorkflowOptions()
    ): UUID {
        val client = coreClient ?: throw IllegalStateException("Client not started")

        val response = client.startWorkflow(
            workflowKind = workflowKind,
            input = serializer.serialize(input),
            queue = options.queue ?: queue,
            workflowVersion = options.workflowVersion,
            idempotencyKey = options.idempotencyKey
        )

        return UUID.fromString(response.workflowExecutionId)
    }

    /**
     * Query workflow state.
     *
     * Queries allow you to read the current state of a workflow without
     * affecting its execution. The query is executed against the workflow's
     * current state.
     *
     * @param workflowExecutionId The workflow execution ID to query
     * @param queryName The name of the query to execute
     * @param params Optional query parameters
     * @return The query result deserialized to type T
     */
    inline fun <reified T> query(
        workflowExecutionId: UUID,
        queryName: String,
        params: Any? = null
    ): T {
        val client = coreClient ?: throw IllegalStateException("Client not started")

        val resultBytes = client.queryWorkflow(
            workflowExecutionId = workflowExecutionId.toString(),
            queryName = queryName,
            params = internalSerializer.serialize(params)
        )

        return internalSerializer.deserialize(resultBytes, T::class.java)
    }

    /**
     * Resolve a durable promise with a value.
     *
     * This allows external systems to resolve promises that were created
     * by workflows using `ctx.promise()`.
     *
     * @param workflowExecutionId The workflow execution ID
     * @param promiseName The name of the promise (as passed to ctx.promise())
     * @param value The value to resolve the promise with
     */
    fun resolvePromise(
        workflowExecutionId: UUID,
        promiseName: String,
        value: Any?
    ) {
        val client = coreClient ?: throw IllegalStateException("Client not started")

        val promiseId = "$workflowExecutionId:$promiseName"
        client.resolvePromise(promiseId, internalSerializer.serialize(value))
    }

    /**
     * Reject a durable promise with an error.
     *
     * This allows external systems to reject promises that were created
     * by workflows using `ctx.promise()`.
     *
     * @param workflowExecutionId The workflow execution ID
     * @param promiseName The name of the promise (as passed to ctx.promise())
     * @param error The error message
     */
    fun rejectPromise(
        workflowExecutionId: UUID,
        promiseName: String,
        error: String
    ) {
        val client = coreClient ?: throw IllegalStateException("Client not started")

        val promiseId = "$workflowExecutionId:$promiseName"
        client.rejectPromise(promiseId, error)
    }

    /**
     * Stop the client gracefully.
     */
    fun stop() {
        if (!started) return

        coreBridge?.initiateShutdown()
        scope.cancel()
        coreBridge?.close()
        coreClient?.close()
        started = false
    }

    override fun close() {
        stop()
    }

}

/**
 * Options for starting a workflow.
 */
data class StartWorkflowOptions(
    val queue: String? = null,
    val workflowVersion: String? = null,
    val idempotencyKey: String? = null
)
