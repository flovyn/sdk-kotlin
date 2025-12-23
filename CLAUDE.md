# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build all modules
./gradlew build

# Run unit tests (excludes E2E tests)
./gradlew test

# Run E2E tests (requires Flovyn server or dev infrastructure)
FLOVYN_E2E_USE_DEV_INFRA=1 ./gradlew :sdk-jackson:e2eTest

# Run a single test class
./gradlew :core:test --tests "ai.flovyn.sdk.workflow.SomeTest"

# Run examples
./gradlew :examples:runHelloWorld
./gradlew :examples:runOrderProcessing
```

## Architecture

This is the official Kotlin SDK for Flovyn, a workflow orchestration platform with deterministic replay. The SDK uses a shared Rust core (`flovyn-core` from the `sdk-rust` repo) via uniffi-generated FFI bindings. The Rust core handles all complex logic (determinism, replay, state machines, gRPC communication) while this SDK provides idiomatic Kotlin APIs.

### Module Structure

- **native**: JNA-based native library loader that extracts and loads platform-specific FFI binaries (`libflovyn_ffi.dylib/so/dll`) from JAR resources. Supported platforms: Linux/macOS/Windows on x86_64 and aarch64.

- **core**: Core SDK abstractions independent of serialization:
  - `ai.flovyn.core.CoreBridge` / `CoreClientBridge`: Wraps FFI bindings for worker and client operations
  - `ai.flovyn.sdk.workflow.WorkflowDefinition` / `WorkflowContext`: Base classes for defining workflows with deterministic execution
  - `ai.flovyn.sdk.task.TaskDefinition` / `TaskContext`: Base classes for defining tasks (side-effectful operations)
  - `ai.flovyn.sdk.worker.WorkflowWorker` / `TaskWorker`: Poll-based workers that process activations from the server
  - `ai.flovyn.sdk.client.FlovynClient`: Main entry point managing workers and workflow execution

- **sdk-jackson**: Jackson-based serialization layer. Provides `FlovynClientBuilder` for constructing clients with automatic JSON serialization of data classes.

- **examples**: Sample applications demonstrating SDK usage.

### Key Patterns

**Workflow Determinism**: All non-deterministic operations within workflows must use `WorkflowContext` methods (`currentTimeMillis()`, `randomUUID()`, `random()`) instead of standard library equivalents. The FFI layer records/replays these operations for event sourcing.

**Typed vs Dynamic Definitions**:
- `WorkflowDefinition<INPUT, OUTPUT>` / `TaskDefinition<INPUT, OUTPUT>`: Strongly-typed with Kotlin data classes
- `DynamicWorkflowDefinition` / `DynamicTaskDefinition`: Map-based input/output for flexible schemas

**Worker Loop**: Workers continuously poll the server via `CoreBridge.pollWorkflowActivation()` / `pollTaskActivation()`, execute registered definitions, and complete with status (Completed/Suspended/Failed/Cancelled).

**Suspension Model**: Workflows suspend via `WorkflowSuspendedException` when awaiting async operations (tasks, timers, promises). The FFI context accumulates commands that are sent to the server on completion.

**Activation Protocol**: The core uses an activation-based protocol. Workers poll for activations, execute workflow/task code, and complete with results or commands.

## Native Library Development

Native binaries come from the `flovyn/sdk-rust` repository and are downloaded during CI. To develop locally with custom bindings:
1. Build the Rust FFI library
2. Place platform binaries in `native/src/main/resources/natives/{platform}/`
3. Copy Kotlin bindings to `native/src/main/kotlin/uniffi/flovyn_ffi/`

Alternatively, set `uniffi.component.flovyn_ffi.libraryOverride` or `jna.library.path` system properties.
