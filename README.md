# Flovyn Kotlin SDK

The official Kotlin SDK for [Flovyn](https://flovyn.ai), a workflow orchestration platform with deterministic replay.

## Features

- **Annotation-free API**: Define workflows and tasks through class inheritance
- **Type-safe**: Full Kotlin type system support with reified generics
- **Coroutines**: Native suspend function support for async operations
- **Jackson serialization**: Zero-configuration JSON handling with data classes
- **Deterministic execution**: Automatic replay support with event sourcing

## Installation

Add the SDK dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.flovyn:flovyn-sdk:1.0.0")
}
```

## Quick Start

### Define a Workflow

```kotlin
import ai.flovyn.sdk.workflow.WorkflowDefinition
import ai.flovyn.sdk.workflow.WorkflowContext

// Input/output data classes
data class GreetingInput(val name: String)
data class GreetingOutput(val message: String, val timestamp: Long)

// Workflow definition
class GreetingWorkflow : WorkflowDefinition<GreetingInput, GreetingOutput>() {
    override val kind = "greeting-workflow"

    override suspend fun execute(ctx: WorkflowContext, input: GreetingInput): GreetingOutput {
        val message = "Hello, ${input.name}!"
        ctx.set("greeting", message)
        return GreetingOutput(
            message = message,
            timestamp = ctx.currentTimeMillis()
        )
    }
}
```

### Define a Task

```kotlin
import ai.flovyn.sdk.task.TaskDefinition
import ai.flovyn.sdk.task.TaskContext

data class EmailInput(val to: String, val subject: String, val body: String)
data class EmailResult(val sent: Boolean, val messageId: String?)

class SendEmailTask : TaskDefinition<EmailInput, EmailResult>() {
    override val kind = "send-email"
    override val timeoutSeconds = 30

    override suspend fun execute(input: EmailInput, context: TaskContext): EmailResult {
        context.log("info", "Sending email to ${input.to}")
        // Send email...
        return EmailResult(sent = true, messageId = "msg-123")
    }
}
```

### Create and Start the Client

```kotlin
import ai.flovyn.sdk.client.FlovynClient
import java.util.UUID

suspend fun main() {
    val tenantId = UUID.fromString("your-tenant-id")

    val client = FlovynClient.builder()
        .serverAddress("localhost", 9090)
        .tenantId(tenantId)
        .taskQueue("default")
        .registerWorkflow(GreetingWorkflow())
        .registerTask(SendEmailTask())
        .build()

    // Start the worker
    client.start()

    // Start a workflow
    val executionId = client.startWorkflow(
        workflowKind = "greeting-workflow",
        input = GreetingInput(name = "World")
    )
    println("Started workflow: $executionId")

    // Keep running
    // client.stop() when done
}
```

## API Reference

### WorkflowDefinition

Base class for defining workflows:

```kotlin
abstract class WorkflowDefinition<INPUT, OUTPUT> {
    abstract val kind: String               // Unique workflow identifier

    open val name: String                   // Human-readable name
    open val version: SemanticVersion       // Version for compatibility
    open val description: String?           // Optional description
    open val cancellable: Boolean           // Whether workflow can be cancelled

    abstract suspend fun execute(ctx: WorkflowContext, input: INPUT): OUTPUT
}
```

### WorkflowContext

Available operations within a workflow:

```kotlin
interface WorkflowContext {
    // Deterministic operations
    fun currentTimeMillis(): Long           // Deterministic time
    fun randomUUID(): UUID                  // Deterministic random UUID
    fun random(): Random                    // Deterministic random generator

    // Task scheduling
    suspend fun <T : Any> schedule(
        taskType: String,
        input: Any?,
        options: ScheduleTaskOptions = ScheduleTaskOptions.DEFAULT
    ): T

    suspend fun <T : Any> scheduleAsync(taskType: String, input: Any?): Deferred<T>

    // State management
    suspend fun <T> get(key: String): T?
    suspend fun <T> set(key: String, value: T)
    suspend fun clear(key: String)

    // Timers
    suspend fun sleep(duration: Duration)

    // Promises (external signals)
    suspend fun <T> promise(name: String, timeout: Duration? = null): DurablePromise<T>

    // Cancellation
    fun isCancellationRequested(): Boolean
    suspend fun checkCancellation()
}
```

### TaskDefinition

Base class for defining tasks:

```kotlin
abstract class TaskDefinition<INPUT, OUTPUT> {
    abstract val kind: String               // Unique task identifier

    open val name: String                   // Human-readable name
    open val version: SemanticVersion       // Version for compatibility
    open val timeoutSeconds: Int?           // Execution timeout
    open val retryConfig: RetryConfig       // Retry configuration

    abstract suspend fun execute(input: INPUT, context: TaskContext): OUTPUT
}
```

### TaskContext

Available operations within a task:

```kotlin
interface TaskContext {
    val taskExecutionId: String
    val input: Any?
    val attempt: Int

    suspend fun reportProgress(progress: Double, details: String? = null)
    suspend fun heartbeat()
    suspend fun log(level: String, message: String)

    fun isCancelled(): Boolean
    fun checkCancellation()
}
```

## Dynamic Workflows and Tasks

For workflows/tasks that don't need typed input/output:

```kotlin
// Dynamic workflow with Map input/output
class DynamicWorkflow : DynamicWorkflowDefinition() {
    override val kind = "dynamic-workflow"

    override suspend fun execute(ctx: WorkflowContext, input: Map<String, Any?>): Map<String, Any?> {
        return mapOf("result" to "processed")
    }
}

// Dynamic task
class DynamicTask : DynamicTaskDefinition() {
    override val kind = "dynamic-task"

    override suspend fun execute(input: Map<String, Any?>, context: TaskContext): Map<String, Any?> {
        return mapOf("success" to true)
    }
}
```

## Workflow Hooks

Monitor workflow lifecycle events:

```kotlin
import ai.flovyn.sdk.client.WorkflowHook
import java.util.UUID

class MetricsHook : WorkflowHook {
    override suspend fun onWorkflowStarted(
        workflowExecutionId: UUID,
        workflowKind: String,
        input: Map<String, Any?>
    ) {
        metrics.increment("workflow.started", tags = mapOf("kind" to workflowKind))
    }

    override suspend fun onWorkflowCompleted(
        workflowExecutionId: UUID,
        workflowKind: String,
        result: Any?
    ) {
        metrics.increment("workflow.completed", tags = mapOf("kind" to workflowKind))
    }

    override suspend fun onWorkflowFailed(
        workflowExecutionId: UUID,
        workflowKind: String,
        error: Throwable
    ) {
        metrics.increment("workflow.failed", tags = mapOf("kind" to workflowKind))
    }
}

// Register hook
val client = FlovynClient.builder()
    .registerHook(MetricsHook())
    // ...
    .build()
```

## Determinism Requirements

Workflows must be deterministic for replay. Use context methods instead of standard library:

```kotlin
// CORRECT - deterministic
val uuid = ctx.randomUUID()
val timestamp = ctx.currentTimeMillis()
val randomValue = ctx.random().nextInt()

// INCORRECT - non-deterministic
val uuid = UUID.randomUUID()              // Different on replay!
val timestamp = System.currentTimeMillis() // Different on replay!
val randomValue = kotlin.random.Random.nextInt() // Different on replay!
```

## Serialization

The SDK uses Jackson for JSON serialization. Data classes work out of the box:

```kotlin
// This just works - no annotations needed
data class OrderInput(
    val customerId: String,
    val items: List<OrderItem>,
    val shippingAddress: String
)

data class OrderItem(
    val productId: String,
    val quantity: Int,
    val pricePerUnit: Double
)
```

## Requirements

- JDK 17+
- Kotlin 1.9+
- Flovyn Server running

## Native Library

The SDK includes native libraries for FFI communication with the Flovyn core. Supported platforms:
- Linux x86_64
- macOS x86_64
- macOS aarch64 (Apple Silicon)

## Building from Source

```bash
# Build all modules
./gradlew build

# Run unit tests
./gradlew test

# Run E2E tests (requires Flovyn server)
FLOVYN_E2E_USE_DEV_INFRA=1 ./gradlew :sdk-jackson:e2eTest

# Run examples
./gradlew :examples:run
```

## License

Apache 2.0
