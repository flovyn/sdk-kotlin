# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

Use [mise](https://mise.jdx.dev/) for all development tasks:

```bash
# Build all modules
mise run build

# Run unit tests (excludes E2E tests)
mise run test

# Run E2E tests (uses Testcontainers - requires Docker and native library)
mise run test:e2e

# Check code
mise run check

# Clean build artifacts
mise run clean

# Run examples
mise run example:hello        # Hello world
mise run example:order        # Order processing

# Run a single test class (use gradlew directly)
./gradlew :worker-sdk:test --tests "ai.flovyn.sdk.workflow.SomeTest"

# Code formatting with ktlint
./gradlew ktlintCheck              # Check code style
./gradlew ktlintFormat             # Auto-format code
```

## Architecture

This is the official Kotlin SDK for Flovyn, a workflow orchestration platform with deterministic replay. The SDK uses a shared Rust core (`flovyn-core` from the `sdk-rust` repo) via uniffi-generated FFI bindings. The Rust core handles all complex logic (determinism, replay, state machines, gRPC communication) while this SDK provides idiomatic Kotlin APIs.

### Module Structure

- **worker-native**: JNA-based native library loader that extracts and loads platform-specific FFI binaries (`libflovyn_worker_ffi.dylib/so/dll`) from JAR resources. Supported platforms: Linux/macOS/Windows on x86_64 and aarch64.

- **worker-sdk**: Core SDK abstractions independent of serialization:
  - `ai.flovyn.core.CoreBridge` / `CoreClientBridge`: Wraps FFI bindings for worker and client operations
  - `ai.flovyn.sdk.workflow.WorkflowDefinition` / `WorkflowContext`: Base classes for defining workflows with deterministic execution
  - `ai.flovyn.sdk.task.TaskDefinition` / `TaskContext`: Base classes for defining tasks (side-effectful operations)
  - `ai.flovyn.sdk.worker.WorkflowWorker` / `TaskWorker`: Poll-based workers that process activations from the server
  - `ai.flovyn.sdk.client.FlovynClient`: Main entry point managing workers and workflow execution

- **worker-sdk-jackson**: Jackson-based serialization layer. Provides `FlovynClientBuilder` for constructing clients with automatic JSON serialization of data classes.

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

Native binaries come from the `flovyn/sdk-rust` repository. Use the provided scripts to get or build the native library:

```bash
# Option 1: Build from local sdk-rust (requires sdk-rust at ../sdk-rust)
./bin/dev/update-native.sh

# Option 2: Download from GitHub releases (Linux/Windows only)
./bin/dev/update-native.sh --download         # Latest release
./bin/dev/update-native.sh --download v0.1.7  # Specific version

# Option 3: Regenerate Kotlin bindings only (if you already have the library)
./bin/dev/update-native.sh --bindings

# Option 4: Direct download from GitHub releases
./bin/download-ffi.sh v0.1.7 linux-x86_64
```

The scripts place:
- Native libraries in `worker-native/src/main/resources/natives/{platform}/`
- Kotlin bindings in `worker-native/src/main/kotlin/uniffi/flovyn_worker_ffi/`

Supported platforms: `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`
