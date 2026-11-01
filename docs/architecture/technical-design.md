# admcp - Technical Design (Revision 3.2)

**Status:** Approved baseline for implementation planning — Revision 3.2 amendments  
**Architecture:** Hybrid, local-first  
**Language:** Kotlin/JVM  
**Host platforms:** Windows, Linux, macOS  
**Primary mode:** Local Android development workspace  
**Secondary mode:** Installed debuggable APK  
**Interfaces:** MCP, ADB, Android CLI, JDI/JDWP, DAP, LLDB, Dart VM Service through official adapters

## 1. Product vision

Build a high-quality MCP server that gives AI agents real Android debugging capabilities: source-level Java/Kotlin and C/C++ debugging, Flutter/Dart debugging, live UI inspection and interaction, screenshots, continuous logcat capture, profiling, artifact management, and evidence-based diagnostics. It must be a real debugging environment, not a command wrapper.

## 2. Goals and constraints

- Full Java/Kotlin debugging through JDI/JDWP.
- Full native C/C++ debugging through LLDB and device-side `lldb-server`, included in v1.
- Flutter/Dart debugging through official DAP adapters.
- Android CLI is the primary Android automation and tooling interface; ADB remains the transport, device-management and compatibility foundation.
- UI Automator is a fallback where Android CLI does not provide an operation or is unavailable.
- Support debuggable developer builds; do not bypass Android security, require root, or promise debugging arbitrary non-debuggable Release apps.
- Local process on the developer's computer; default MCP transport is STDIO.
- Windows, Linux and macOS support must be tested independently.
- No mandatory SDK/instrumentation embedded in the app.
- Keep raw evidence separate from parsed, indexed, redacted and interpreted data.
- Every capability must be probed; tool installation alone does not prove runtime capability.
- All claims and diagnoses must identify their evidence and limitations.

## 3. Selected hybrid architecture

The Kotlin/JVM server owns MCP, workspace discovery, toolchain management, process supervision, devices, sessions, capabilities, execution coordination, event correlation, artifacts and normalized APIs. Existing tools own mature protocols and platform-specific debugging: JDI/JDWP for Java/Kotlin, `lldb-dap`/LLDB plus `lldb-server` for native, official Dart/Flutter DAP adapters for Flutter, and Android CLI for UI/profiling/artifact analysis. Do not reimplement JDWP, DAP, LLDB Remote Protocol or Dart VM Service.

## 4. Principal subsystems

- **Toolchain Manager:** resolves and validates JDK/JDI, Android SDK/platform-tools/build-tools, Android CLI, Gradle wrapper/AGP, NDK, CMake/Ninja, host LLDB, `lldb-dap`, ABI-matched `lldb-server`, Flutter and Dart. Records versions, paths, provenance and compatibility checks. Does not silently install or update tools.
- **Device Manager:** ADB devices, USB/wireless connections, metadata, ABI, API level, process/package discovery, reconnects.
- **Workspace Manager:** Gradle/AGP projects, modules, variants, Java/Kotlin, CMake/ndk-build, Flutter projects, build outputs, mappings and symbols.
- **Process Supervisor:** argument-array process launch, stdout/stderr, timeouts, cancellation, exit codes, process-tree cleanup and resource limits.
- **Session Manager:** project/device/package/process association, explicit state machines, independent subsystem and backend state.
- **Capability Registry:** reports `available`, `limited`, `unavailable` or `unknown` based on live probes and evidence.
- **Debugger Orchestrator:** normalized API over JDI, LLDB DAP and Dart/Flutter DAP while preserving backend-specific capabilities.
- **ProcessExecutionCoordinator:** coordinates operations on a shared process without assuming JDI and LLDB can inspect simultaneously. Tracks stop origin, stop scope, affected threads, backend accessibility and monotonically increasing stop epoch.
- **Source & Symbol Resolver:** exact native ELF Build ID/module/ABI/symbol matching; Java/Kotlin source mapping including SMAP and R8 mappings; Dart source URI mapping.
- **Expression Engine:** separate, staged expression parser/resolver. Initial scope is safe read-only locals/fields/arrays/simple property paths. Method invocation is disabled by default and needs explicit policy/approval, timeout and side-effect warning. No promise of full Kotlin language evaluation.
- **AndroidCliBackend:** shared facade for `layout`, `input`, `screen`, `profiler`, `preview`, `analyze` and optional `signals` command families, each a separate adapter with runtime version/capability probing.
- **UI:** Android CLI layout/selectors/input is primary; UI Automator/ADB is fallback. Semantic selector first, visual resolve second, coordinates last. Every action is verified by fresh UI evidence. Compose semantics are not the internal Composable tree.
- **Compose deep inspection:** experimental/optional adapter. Runtime Composition hierarchy, parameters, recomposition counts and composition tracing are not required v1 acceptance criteria. Compose Preview is build-time rendering, not runtime inspection.
- **Logcat Engine:** continuous raw capture from accessible buffers; text is mandatory baseline; `-B` and `--proto` are investigated as optional version/capability-gated formats. Preserve raw bytes/records separately from parser/index. Distinguish `CollectorLoss` (known internal losses) from `DeviceLogLoss` (logd/buffer loss, sometimes unobservable). Report coverage as complete only when proven; otherwise report unverified/partial.
- **Artifact Store/Event Store:** filesystem for large immutable artifacts; SQLite WAL for events, sessions, artifact metadata, loss records, capabilities and indexes. Stable IDs, hashes, quotas and retention.
- **Diagnostic Engine:** Level 1 evidence-based crashes/exceptions/ANR/process-death/interaction failures; Level 2 explicit opt-in profiling through Perfetto, Simpleperf, JVM heap dump, memory tools, FrameTimeline and experimental Compose tooling.
- **MCP:** typed tools, resources for large artifacts, image content for screenshots, pagination, cancellation and explicit errors. STDIO stdout is protocol-only; logs go to stderr. Keep transport-independent business logic; Streamable HTTP is optional later.

## 5. Critical invariants

1. JDI and LLDB are independent backends; the coordinator owns shared process execution state but never fabricates backend availability.
2. A native process-wide stop may make JDI inspection unavailable even while its JDI connection remains attached. Track `attached` separately from `inspectionAvailable`.
3. Stack frames, variables, object handles and frame IDs are scoped to `(sessionId, backendId, processId, stopEpoch, threadId)` and are invalid after resume/step/new stop epoch.
4. UI selector ambiguity must fail closed. Coordinate fallback must be based on a fresh screenshot and verified bounds.
5. Preserve raw log evidence. Never claim absolute zero loss. Every known pipeline loss must be recorded; device-buffer loss may remain unobservable.
6. Distinguish wall-clock, device and monotonic timestamps; never compare clock domains without an explicit offset/uncertainty model.
7. Build scripts and plugins execute arbitrary project code; unknown workspaces require trust approval.
8. Redacted AI-facing data must not mutate original artifacts.
9. Diagnosis is a claim plus evidence references, confidence, hypotheses and limitations—not an unsupported conclusion.
10. Tool versions and capabilities are discovered and validated, not inferred from PATH or installation presence.

## 6. Android CLI scope

The Android CLI backend must probe commands and flags at runtime instead of assuming every installed version has every subcommand. Planned adapters:

- Layout: `android layout --pretty --full` when supported.
- Input: selector-based tap/text/scroll/swipe/key and explicit match policies.
- Screen: capture, annotate and resolve.
- Profiler: Perfetto systemtrace and JVM heapdump; profile-session lifecycle.
- Preview: Compose Preview list/render; explicitly build-time only.
- Analyze: APK/AAB/AAR/JAR metadata and optimization reports.
- Signals: optional authenticated Crashlytics field telemetry, separate from local logcat.

ADB remains available for JDWP forwarding, device discovery, shell operations and fallback capture/control.

## 7. Logcat loss model

`CollectorLoss` covers known losses in the MCP read/write/parser/indexing pipeline. `DeviceLogLoss` covers evidence from logd statistics, ring-buffer eviction or source-side gaps. ADB disconnect windows are known coverage gaps, not a count of lost records. If logd statistics are disabled or buffers are inaccessible, device-side loss status is `UNVERIFIED`, not `ZERO`. Raw capture writer failure transitions the collector to `FAILED`; parser/index queue overflow must be counted and raw data remain replayable where persisted.

## 8. v1 requirements

- Toolchain, device, workspace and session infrastructure.
- Android CLI layout, selector interaction, screenshots and verified UI actions.
- UI Automator/ADB fallback.
- Continuous logcat with raw capture, query, health, loss and coverage reporting.
- JDI Java/Kotlin debugging.
- LLDB/lldb-server native debugging.
- DAP client and Flutter/Dart backend.
- Dual JDI+LLDB coordination with tested process/thread stop behavior.
- Source/symbol resolution sufficient for exact source breakpoints and native symbols.
- Artifact/event store and unified timeline.
- Level 1 evidence-based diagnostics.
- Perfetto systemtrace integration as an opt-in profiling workflow.

## 9. Deferred or optional

- Full Kotlin expression evaluator and arbitrary method invocation.
- Deep Compose runtime hierarchy/recomposition inspection.
- Advanced coroutine introspection.
- Full native heap profiling/Simpleperf coverage beyond validated adapters.
- Firebase Crashlytics signals and field heap dumps.
- Streamable HTTP transport.
- Arbitrary production APK debugging without debuggable access.

## 10. Definition of Done

An AI agent can connect a device, inspect and build a debuggable project, launch it, inspect and interact with the live UI, capture screenshots, collect/query logs with honest coverage reporting, attach JDI and LLDB simultaneously, hit source breakpoints in Kotlin and C++, inspect valid execution state, debug JNI transitions, use Flutter/Dart DAP for Flutter projects, correlate all evidence in one timeline, and retrieve immutable artifacts. The same core behavior must pass automated tests and documented real-device acceptance tests on supported host platforms.


## 11. Revision 3.2 amendments — implementation-critical clarifications

The following requirements refine Revision 3 without changing the selected Hybrid Architecture.

### 11.1 Android CLI feasibility and command scope

Phase 0 must probe the complete relevant command surface, not only runtime UI commands:

- `android describe` for project/module/artifact discovery.
- `android build` for project/target/variant/build-reference behavior.
- `android install` for APK and split APK installation.
- `android run` for launch and build-reference handling.
- `android layout`, `android input`, `android screen` for UI inspection and actions.
- `android profiler`, `android preview`, `android analyze` and optional `android signals`.

For every device-targeted operation, verify explicit device selection. If the installed CLI cannot unambiguously target the requested device, fail closed when multiple devices are connected. Never assume that the CLI inherits the ADB serial selected by another component.

### 11.2 Public MCP API completeness

Freeze the v1 public tool catalog before Phase 3. Every capability included in v1 release acceptance must have a public MCP tool or resource. Optional integrations may be present as capability-gated tools that return a structured `UNAVAILABLE`/`LIMITED` result with a reason.

The catalog must include, at minimum:

- Device/workspace: `device_list`, `device_inspect`, `toolchain_probe`, `workspace_open`, `workspace_describe`, `workspace_trust`, `workspace_revoke_trust`, `app_build`, `app_install`, `app_launch`.
- Session/UI: `session_start`, `session_status`, `session_stop`, `ui_inspect`, `ui_find`, `ui_act`, `ui_screenshot`.
- Logcat: `logcat_start`, `logcat_stop`, `logcat_query`, `logcat_statistics`, `logcat_health`.
- Debugging: `debug_attach`, `debug_detach`, `debug_status`, `debug_breakpoint_add`, `debug_breakpoints_list`, `debug_breakpoint_remove`, `debug_pause`, `debug_continue`, `debug_step`, `debug_threads`, `debug_stack`, `debug_variables`, `debug_evaluate`, `debug_modules`, `debug_registers`, `debug_memory_read`.
- Profiling/artifacts: `profiler_systemtrace_start`, `profiler_systemtrace_stop`, `profiler_heapdump_capture`, `performance_trace_analyze`, `preview_list`, `preview_render`, `analyze_artifact`.
- Diagnostics/evidence: `diagnostics_timeline`, `diagnostics_crashes`, `diagnostics_analyze`, `artifact_get`.
- Optional integrations: `signals_crashes` and experimental `compose_inspect`.

### 11.3 Process identity and suspension ownership

A PID is not a process identity. `ProcessRef` must include a unique `ProcessInstanceId`/generation token and an optional device-observed start token. A newly observed process after PID reuse gets a different instance ID.

Backend execution events include `backendSequence` and `operationId` when available. Events for an old process instance or older backend sequence must not rewind current state.

The coordinator stores owner-scoped `SuspensionLease` records. A resume operation may release only the requesting backend's lease. It must not release a different backend's stop. The process-wide execution snapshot is distinct from every backend's attached/inspectable state. Thread and process stop scopes remain separate.

### 11.4 Expression safety

Use one expression policy for Java/Kotlin and native inspection. V1 supports read-only locals, fields, arrays and bounded object inspection. Method invocation, assignments and arbitrary code execution are disabled by default.

Native variables must be read through DAP scopes/variables or LLDB's non-executing variable inspection. Do not expose a generic LLDB `evaluate(expression)` as the default native inspection path. Bounded memory reads are separate operations. Any future method invocation or arbitrary evaluation requires a separately declared capability, explicit approval, timeout and side-effect warning.

### 11.5 Workspace trust and UI action policy

Workspace trust means consent for project-controlled code to execute with the current user's OS permissions; it is not a sandbox. Enforce canonical path and symlink checks, revalidation before use, explicit environment allowlists and documented limitations. Only claim network/filesystem isolation when an OS-level sandbox actually enforces it.

UI selector uniqueness does not make an action safe. A separate `UiActionPolicy` classifies actions as read-only, reversible, external-side-effect, destructive or unknown. Destructive, external-side-effect and unknown-risk operations require explicit confirmation or a pre-approved policy.

### 11.6 Runtime reliability refinements

- ProcessSupervisor concurrently drains stdout/stderr, bounds buffers, exposes truncation/backpressure and terminates process trees.
- Artifact storage uses recoverable staging/commit states. Startup reconciliation detects orphan files, missing files and interrupted writes across filesystem and SQLite metadata.
- Event timelines preserve source sequence and clock domains. Clock calibration records estimated offset and uncertainty; overlapping uncertainty must not be presented as a proven total order.
- Redaction combines explicit patterns, structured sensitive-field handling and heuristic detectors. It never mutates raw evidence and never claims exhaustive secret detection.

### 11.7 Incremental release gates

- **Milestone A:** usable Android workspace, UI, screenshots, device actions and logcat MCP.
- **Milestone B:** production-quality Java/Kotlin JDI debugging and source mapping.
- **Milestone C:** full v1 including LLDB/native, Flutter/Dart, dual-debug coordination, evidence-based diagnostics and cross-platform release acceptance.

Passing Milestone A or B is not equivalent to completing v1.


## 12. Revision 3.2 amendments — API completeness and implementation gates

Revision 3.2 incorporates the follow-up implementation review. It preserves the selected architecture and clarifies the following release-critical requirements.

### 12.1 Complete Android CLI feasibility matrix

Phase 0 probes `android describe`, `build`, `install` and `run` in addition to `layout`, `input`, `screen`, `profiler`, `preview`, `analyze` and optional `signals`. Probe multi-device behavior independently for every device-targeted command family, including install, run, UI input, screen targeting and profiling. Build references, artifact identity, process/PID output and explicit device routing must be recorded.

### 12.2 Public MCP catalog contract

`docs/architecture/mcp-tool-catalog-v1.md` is the single source of truth for public MCP tools and resources. It specifies exact names, input/output schemas, capabilities, side-effect classifications, trust/confirmation behavior and optional availability. The runtime registry is validated against it. No capability is accepted for v1 unless exposed by a catalogued tool/resource or explicitly deferred.

### 12.3 Process identity and suspension ownership

`ProcessRef` includes device ID, PID and a unique `ProcessInstanceId`, plus a device-observed start token when available. A PID reuse creates a new process instance. Backend events carry per-backend sequence and operation ID where available. Suspension is represented by owner-scoped leases with `ACTIVE`, `RELEASE_PENDING`, `RELEASED` and `INDETERMINATE` states. A backend may mark only its own lease for release; it is marked `RELEASED` only after a correlated backend acknowledgement. An adapter crash leaves its lease `INDETERMINATE` until target state is reconciled. A process-wide continue is rejected while other unresolved leases or ambiguous stop scopes remain.

### 12.4 Expression safety

Native v1 inspection uses DAP scopes/variables and bounded memory reads. Native freeform expression evaluation is not exposed. Java/Kotlin and native share a read-only expression policy. Method invocation, assignment and arbitrary code execution remain disabled by default. Flutter/Dart evaluation, where exposed by the official adapter, must be separately capability- and side-effect-classified; it is not implicitly treated as read-only.

### 12.5 Workspace and UI action trust

Workspace trust is informed consent for project-controlled code to run with current OS-user privileges, not sandboxing. Canonical path and symlink checks must be revalidated immediately before file use; environment variables use an allowlist. UI actions are separately risk-assessed; destructive, external-side-effect and unknown-risk actions require explicit host/MCP elicitation confirmation or a pre-approved policy. A unique selector is not consent. If the client cannot obtain confirmation, the action is not executed.

### 12.6 Reliability and data integrity

ProcessSupervisor must concurrently drain both output streams, enforce bounded buffering/backpressure and terminate process trees. Artifact storage reconciles interrupted filesystem/SQLite commits at startup. Event ordering records source sequence and clock uncertainty. Raw diagnostic evidence remains immutable and separate from redacted agent-facing views.

### 12.7 Release gates

- **Milestone A:** usable Android workspace, device management, UI/screenshot/control, and Logcat MCP with a complete public catalog subset.
- **Milestone B:** production-quality Java/Kotlin JDI debugging with source mapping and handle invalidation.
- **Milestone C:** full v1 including native LLDB, Flutter/Dart, coordinated dual debugging, diagnostics, profiling adapters and cross-platform acceptance.

The public tool catalog and its contract tests must be complete before the first usable MCP milestone is considered releasable.


### 12.8 Public trust lifecycle and backend detach

Workspace trust grant is a public explicit operation (`workspace_trust`) requiring a short-lived host/MCP elicitation approval receipt; if the MCP client cannot obtain consent, trust must not be granted. `workspace_revoke_trust` immediately invalidates a grant and fails safe. The trusted root is derived by the server from the canonical workspace path and cannot be expanded by tool arguments. `app_build`, preview rendering and other project-code execution must validate the scoped grant immediately before execution. `debug_detach` detaches one debugger backend without implicitly terminating the app or session.


### 12.9 Approval delivery

Workspace trust grants and risky UI actions use a host-mediated approval broker. Approval receipts are server-issued, short-lived and single-use. Tool arguments cannot self-assert approval. Unsupported or declined confirmation is a typed, fail-closed result.


### 12.10 Build-system detection versus support

The project detector may identify Gradle and Bazel Android project metadata separately. Project detection does not imply that a build system is supported: Bazel is marked `AVAILABLE` only when the Android CLI `describe/build/install/run` path passes Phase 0 for that project type. Otherwise it remains detected but explicitly `LIMITED` or `UNAVAILABLE`.


### 12.11 Milestone-aware MCP exposure

The complete v1 tool catalog and schemas are defined before implementation, but intermediate releases expose tools cumulatively by milestone. Milestone A advertises only implemented gate-A tools; Milestone B advertises gate-A and gate-B tools; Full v1 advertises all gate-A/B/C tools. The catalog resource may describe future-gate entries, but MCP `tools/list` must never advertise a tool without a concrete handler in the running build. Full-v1 startup requires every non-optional catalog tool to have a handler and optional entries to have capability-aware handlers that return typed `UNAVAILABLE` results when needed.

The runtime `tools/list` result is cumulative and profile-filtered: Milestone A exposes only gate-A tools, Milestone B exposes gate-A and gate-B tools, and Full v1 exposes all gate-A/B/C tools. The catalog resource retains the full versioned contract, but later-gate tools must not be advertised until a concrete handler is present in the running build.
