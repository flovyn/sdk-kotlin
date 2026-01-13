package ai.flovyn.sdk.workflow

import ai.flovyn.sdk.common.SemanticVersion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for WorkflowDefinition schema support.
 */
class WorkflowDefinitionSchemaTest {

    // Test data classes
    data class OrderInput(val orderId: String, val amount: Double)
    data class OrderOutput(val success: Boolean)

    /**
     * A workflow with default schema (null).
     */
    class DefaultSchemaWorkflow : WorkflowDefinition<OrderInput, OrderOutput>() {
        override val kind = "default-schema-workflow"

        override suspend fun execute(ctx: WorkflowContext, input: OrderInput): OrderOutput {
            return OrderOutput(success = true)
        }
    }

    /**
     * A workflow with explicit JSON Schema.
     */
    class ExplicitSchemaWorkflow : WorkflowDefinition<OrderInput, OrderOutput>() {
        override val kind = "explicit-schema-workflow"
        override val name = "Explicit Schema Workflow"
        override val description = "A workflow with explicit schema"

        override val inputSchema: String = """
            {
                "type": "object",
                "properties": {
                    "orderId": { "type": "string" },
                    "amount": { "type": "number" }
                },
                "required": ["orderId", "amount"]
            }
        """.trimIndent()

        override val outputSchema: String = """
            {
                "type": "object",
                "properties": {
                    "success": { "type": "boolean" }
                },
                "required": ["success"]
            }
        """.trimIndent()

        override suspend fun execute(ctx: WorkflowContext, input: OrderInput): OrderOutput {
            return OrderOutput(success = true)
        }
    }

    @Test
    fun `default schema is null`() {
        val workflow = DefaultSchemaWorkflow()
        assertNull(workflow.inputSchema, "Default inputSchema should be null")
        assertNull(workflow.outputSchema, "Default outputSchema should be null")
    }

    @Test
    fun `explicit schema is returned`() {
        val workflow = ExplicitSchemaWorkflow()

        // Verify schema content
        val inputSchema = requireNotNull(workflow.inputSchema) { "inputSchema should not be null" }
        val outputSchema = requireNotNull(workflow.outputSchema) { "outputSchema should not be null" }

        assert(inputSchema.contains("\"type\": \"object\"")) {
            "inputSchema should contain type: object"
        }
        assert(inputSchema.contains("\"orderId\"")) {
            "inputSchema should contain orderId property"
        }
        assert(inputSchema.contains("\"amount\"")) {
            "inputSchema should contain amount property"
        }
        assert(outputSchema.contains("\"success\"")) {
            "outputSchema should contain success property"
        }
    }

    @Test
    fun `workflow properties are correctly set`() {
        val workflow = ExplicitSchemaWorkflow()

        assertEquals("explicit-schema-workflow", workflow.kind)
        assertEquals("Explicit Schema Workflow", workflow.name)
        assertEquals("A workflow with explicit schema", workflow.description)
        assertEquals(SemanticVersion.DEFAULT, workflow.version)
    }
}
