package ai.flovyn.examples

import ai.flovyn.sdk.common.SemanticVersion
import ai.flovyn.sdk.task.RetryConfig
import ai.flovyn.sdk.task.ScheduleTaskOptions
import ai.flovyn.sdk.task.TaskContext
import ai.flovyn.sdk.task.TaskDefinition
import ai.flovyn.sdk.workflow.WorkflowContext
import ai.flovyn.sdk.workflow.WorkflowDefinition
import ai.flovyn.sdk.workflow.schedule
import java.util.UUID

// --- Data Classes ---

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

data class OrderOutput(
    val orderId: String,
    val status: String,
    val totalAmount: Double,
    val trackingNumber: String?
)

data class PaymentInput(
    val orderId: String,
    val amount: Double,
    val customerId: String
)

data class PaymentResult(
    val success: Boolean,
    val transactionId: String?,
    val errorMessage: String?
)

data class InventoryInput(
    val orderId: String,
    val items: List<OrderItem>
)

data class InventoryResult(
    val reserved: Boolean,
    val failedItems: List<String>
)

data class ShippingInput(
    val orderId: String,
    val items: List<OrderItem>,
    val shippingAddress: String
)

data class ShippingResult(
    val trackingNumber: String,
    val estimatedDelivery: String
)

// --- Tasks ---

/**
 * Task to process payment for an order.
 */
class ProcessPaymentTask : TaskDefinition<PaymentInput, PaymentResult>() {
    override val kind = "process-payment"
    override val name = "Process Payment"
    override val version = SemanticVersion(1, 0, 0)
    override val description = "Processes payment for an order"
    override val timeoutSeconds = 30
    override val retryConfig = RetryConfig(
        maxAttempts = 3,
        initialDelayMs = 1000,
        maxDelayMs = 10000,
        backoffMultiplier = 2.0
    )

    override suspend fun execute(input: PaymentInput, context: TaskContext): PaymentResult {
        context.log("info", "Processing payment for order ${input.orderId}, amount: ${input.amount}")

        // Report progress
        context.reportProgress(0.5, "Contacting payment provider")

        // Simulate payment processing
        // In a real implementation, this would call a payment gateway

        context.reportProgress(1.0, "Payment completed")

        return PaymentResult(
            success = true,
            transactionId = "txn-${UUID.randomUUID()}",
            errorMessage = null
        )
    }
}

/**
 * Task to reserve inventory for an order.
 */
class ReserveInventoryTask : TaskDefinition<InventoryInput, InventoryResult>() {
    override val kind = "reserve-inventory"
    override val name = "Reserve Inventory"
    override val version = SemanticVersion(1, 0, 0)
    override val description = "Reserves inventory items for an order"
    override val timeoutSeconds = 20

    override suspend fun execute(input: InventoryInput, context: TaskContext): InventoryResult {
        context.log("info", "Reserving inventory for order ${input.orderId}")

        // Simulate inventory check
        val totalItems = input.items.size
        input.items.forEachIndexed { index, item ->
            context.reportProgress(
                (index + 1.0) / totalItems,
                "Checking item ${item.productId}"
            )
        }

        return InventoryResult(
            reserved = true,
            failedItems = emptyList()
        )
    }
}

/**
 * Task to arrange shipping for an order.
 */
class ArrangeShippingTask : TaskDefinition<ShippingInput, ShippingResult>() {
    override val kind = "arrange-shipping"
    override val name = "Arrange Shipping"
    override val version = SemanticVersion(1, 0, 0)
    override val description = "Arranges shipping for an order"
    override val timeoutSeconds = 30

    override suspend fun execute(input: ShippingInput, context: TaskContext): ShippingResult {
        context.log("info", "Arranging shipping for order ${input.orderId}")

        context.reportProgress(0.3, "Generating shipping label")
        context.reportProgress(0.6, "Scheduling pickup")
        context.reportProgress(1.0, "Shipping arranged")

        return ShippingResult(
            trackingNumber = "TRK-${UUID.randomUUID().toString().take(8).uppercase()}",
            estimatedDelivery = "3-5 business days"
        )
    }
}

// --- Workflow ---

/**
 * Workflow that orchestrates the complete order processing flow.
 *
 * This workflow demonstrates:
 * - Task scheduling with typed results
 * - State management
 * - Sequential task execution
 * - Error handling patterns
 */
class OrderProcessingWorkflow : WorkflowDefinition<OrderInput, OrderOutput>() {
    override val kind = "order-processing"
    override val name = "Order Processing"
    override val version = SemanticVersion(1, 0, 0)
    override val description = "Processes customer orders including payment, inventory, and shipping"
    override val cancellable = true

    override suspend fun execute(ctx: WorkflowContext, input: OrderInput): OrderOutput {
        // Generate order ID using deterministic UUID
        val orderId = ctx.randomUUID().toString()

        // Store order info in state
        ctx.set("orderId", orderId)
        ctx.set("status", "processing")
        ctx.set("customerId", input.customerId)

        // Calculate total amount
        val totalAmount = input.items.sumOf { it.quantity * it.pricePerUnit }
        ctx.set("totalAmount", totalAmount)

        // Step 1: Reserve inventory
        ctx.set("status", "reserving-inventory")
        val inventoryResult = ctx.schedule<InventoryResult>(
            taskType = "reserve-inventory",
            input = InventoryInput(
                orderId = orderId,
                items = input.items
            )
        )

        if (!inventoryResult.reserved) {
            ctx.set("status", "failed-inventory")
            return OrderOutput(
                orderId = orderId,
                status = "failed",
                totalAmount = totalAmount,
                trackingNumber = null
            )
        }

        // Step 2: Process payment
        ctx.set("status", "processing-payment")
        val paymentResult = ctx.schedule<PaymentResult>(
            taskType = "process-payment",
            input = PaymentInput(
                orderId = orderId,
                amount = totalAmount,
                customerId = input.customerId
            ),
            options = ScheduleTaskOptions(
                timeoutSeconds = 60
            )
        )

        if (!paymentResult.success) {
            ctx.set("status", "failed-payment")
            return OrderOutput(
                orderId = orderId,
                status = "payment-failed",
                totalAmount = totalAmount,
                trackingNumber = null
            )
        }

        ctx.set("transactionId", paymentResult.transactionId)

        // Step 3: Arrange shipping
        ctx.set("status", "arranging-shipping")
        val shippingResult = ctx.schedule<ShippingResult>(
            taskType = "arrange-shipping",
            input = ShippingInput(
                orderId = orderId,
                items = input.items,
                shippingAddress = input.shippingAddress
            )
        )

        // Update final status
        ctx.set("status", "completed")
        ctx.set("trackingNumber", shippingResult.trackingNumber)

        return OrderOutput(
            orderId = orderId,
            status = "completed",
            totalAmount = totalAmount,
            trackingNumber = shippingResult.trackingNumber
        )
    }
}

/**
 * Main entry point for the OrderProcessing example.
 */
fun main() {
    println("Order Processing Example")
    println("========================")
    println()
    println("This example demonstrates:")
    println("  - Task definitions with typed input/output")
    println("  - Workflow orchestrating multiple tasks")
    println("  - State management during workflow execution")
    println("  - Retry configuration for tasks")
    println()

    val workflow = OrderProcessingWorkflow()
    println("Workflow: ${workflow.name}")
    println("  - kind: ${workflow.kind}")
    println("  - version: ${workflow.version}")
    println()

    val tasks = listOf(
        ProcessPaymentTask(),
        ReserveInventoryTask(),
        ArrangeShippingTask()
    )

    println("Tasks:")
    tasks.forEach { task ->
        println("  - ${task.name} (${task.kind})")
        println("    timeout: ${task.timeoutSeconds}s")
        println("    retries: ${task.retryConfig.maxAttempts}")
    }
    println()
    println("To run this workflow, configure a FlovynClient and register the workflow and tasks.")
}
