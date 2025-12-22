package ai.flovyn.examples

import ai.flovyn.sdk.common.SemanticVersion
import ai.flovyn.sdk.workflow.WorkflowContext
import ai.flovyn.sdk.workflow.WorkflowDefinition

// Input and output data classes
data class GreetingInput(val name: String)
data class GreetingOutput(val message: String, val timestamp: Long)

/**
 * A simple "Hello World" workflow that demonstrates basic workflow concepts.
 */
class GreetingWorkflow : WorkflowDefinition<GreetingInput, GreetingOutput>() {
    override val kind = "greeting-workflow"
    override val name = "Greeting Workflow"
    override val version = SemanticVersion(1, 0, 0)
    override val description = "A simple workflow that greets users"

    override suspend fun execute(ctx: WorkflowContext, input: GreetingInput): GreetingOutput {
        // Use deterministic time from context
        val timestamp = ctx.currentTimeMillis()

        // Store greeting in workflow state
        val greeting = "Hello, ${input.name}!"
        ctx.set("lastGreeting", greeting)

        return GreetingOutput(
            message = greeting,
            timestamp = timestamp
        )
    }
}

/**
 * Main entry point for the HelloWorld example.
 *
 * To run:
 * ```
 * ./gradlew :examples:runHelloWorld
 * ```
 */
fun main() {
    println("Hello World example")
    println("This example demonstrates the Flovyn SDK workflow definition pattern.")
    println()
    println("GreetingWorkflow:")
    println("  - kind: ${GreetingWorkflow().kind}")
    println("  - name: ${GreetingWorkflow().name}")
    println("  - version: ${GreetingWorkflow().version}")
    println()
    println("To run this workflow, you need a Flovyn server running and a FlovynClient configured.")
}
