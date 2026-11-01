# admcp - Public MCP Tool Catalog v1

**Status:** Stable v1 contract<br>
**Catalog version:** `1` (51 public tools: 49 required/core entries and 2 capability-gated optional/experimental entries)  
**Design:** [Technical Design Revision 3.2](technical-design.md)
**Implementation status:** [Implementation status](implementation-status.md)

The machine-readable `mcp-tool-catalog-v1.json` is the canonical source of truth for public MCP tool names, input/output schemas, release gates, capability requirements and side-effect policy. This Markdown document is its human-readable companion and must be kept in parity by contract tests. Backend classes and CLI command names are implementation details; they do not automatically become public MCP tools.

A v1 capability is release-accepted only when it is represented by a tool or resource in this catalog and its handler is registered and covered by contract tests. A capability that cannot be exposed safely or reliably must be explicitly marked deferred and removed from v1 release acceptance.

## 1. Common conventions

### 1.1 Result envelope

Every tool returns structured content using this conceptual envelope (MCP content blocks may additionally include images or resource links):

```json
{
  "ok": true,
  "operationId": "op-...",
  "sessionId": "session-...",
  "capability": {
    "status": "AVAILABLE",
    "backend": "android-cli",
    "version": "...",
    "limitations": []
  },
  "data": {},
  "artifacts": []
}
```

Failures use a typed error object:

```json
{
  "ok": false,
  "operationId": "op-...",
  "error": {
    "code": "DEVICE_AMBIGUOUS",
    "message": "A device must be selected explicitly.",
    "retryable": true,
    "details": {}
  }
}
```

Tool handlers must not convert an unavailable capability into a successful empty result. Confirmation-sensitive calls require a short-lived server-issued approval receipt obtained through host/MCP elicitation; if elicitation is unsupported or declined, fail closed. Return `LIMITED` or `UNAVAILABLE` with an explicit reason.

### 1.2 Identifiers and pagination

- `deviceId`, `workspaceId`, `sessionId`, `processInstanceId`, `artifactId`, `breakpointId` and `operationId` are opaque server-issued identifiers unless explicitly documented otherwise.
- A process reference contains device ID, PID and a unique process-instance ID; PID alone is never accepted as a durable identity for debugger state.
- Debug frame, variable and object handles are bound to backend, process instance and `stopEpoch`.
- List/query operations use bounded page sizes and opaque cursors. A cursor is valid only for the query/session generation that issued it.
- Large binary/text data is returned as an artifact reference or resource, not an unbounded tool response.

### 1.3 Side-effect classes

| Class | Meaning | Approval behavior |
|---|---|---|
| `READ_ONLY` | Reads metadata, logs, hierarchy, variables or stored artifacts | No extra approval beyond session ownership |
| `WORKSPACE_EXECUTION` | Runs build, preview or other project-controlled code | Requires a trusted workspace grant for the operation |
| `DEVICE_MUTATION` | Installs/replaces app or changes device/app state | Explicit operation and target device required |
| `APP_LAUNCH` | Starts/restarts an app or Activity | Explicit target device and package required |
| `UI_ACTION` | Interacts with the running app | `UiActionPolicy`; destructive/external/unknown risk requires confirmation |
| `DEBUGGER_CONTROL` | Pauses, resumes, steps, attaches or changes breakpoints | Requires explicit session/backend/process target; invalid/stale handles rejected |
| `PROFILING` | Starts trace or heap capture | Explicit opt-in; report overhead and target requirements |
| `CLOUD_READ` | Reads optional Crashlytics field telemetry | Requires pre-existing authentication and explicit invocation |

No public tool exposes arbitrary shell execution or unrestricted ADB shell input.

### 1.4 Common enums

The transport schema uses uppercase enum values unless an external protocol requires its own spelling.

- `CapabilityStatus`: `AVAILABLE`, `LIMITED`, `UNAVAILABLE`, `UNKNOWN`.
- `BackendId`: `JDI`, `LLDB`, `DART_DAP`, `ANDROID_CLI`, `UI_AUTOMATOR`.
- `ExecutionState`: `RUNNING`, `STOPPED`, `UNKNOWN`, `EXITED`.
- `StepKind`: `INTO`, `OVER`, `OUT`.
- `StepGranularity`: `STATEMENT`, `LINE`, `INSTRUCTION`.
- `ProfileKind`: `SYSTEM_TRACE`, `JVM_HEAP_DUMP`.
- `UiActionKind`: `TAP`, `LONG_PRESS`, `TYPE_TEXT`, `SCROLL`, `SWIPE`, `KEY`, `BACK`, `HOME`, `WAIT_FOR_STATE`.

## 2. Public tools

### 2.1 Device, toolchain and workspace

| Tool | Required input | Optional input | Result | Capability / side effect |
|---|---|---|---|---|
| `device_list` | none | `includeOffline:boolean=false` | Device IDs, connection state, model, API, ABI and display summary | ADB discovery / `READ_ONLY` |
| `device_inspect` | `deviceId` | `includeProcesses:boolean=true` | Detailed device metadata, accessible buffers, display, process list | ADB / `READ_ONLY` |
| `toolchain_probe` | none | `workspaceId`, `requiredKinds[]`, `targetAbi` | Resolved executable paths, versions, host/target architectures, provenance, hashes where available, capability evidence and errors | Toolchain probes / `READ_ONLY` |
| `workspace_open` | `path` | none | Workspace ID, canonical root, project kind, modules and trust state | Filesystem inspection / `READ_ONLY`; trust grant is a separate explicit operation |
| `workspace_describe` | `workspaceId` | `refresh:boolean=false` | Modules, build systems, targets, variants, package names, source roots and artifacts | `READ_ONLY` |
| `workspace_trust` | `workspaceId`, `allowedOperations[]` | `executionMode` (`SAME_USER_UNSANDBOXED` by default; `OS_SANDBOXED` only if a real sandbox is active) | Scoped trust grant rooted at the server-resolved canonical workspace path and an approval receipt | Explicit host/user approval through MCP elicitation or equivalent host approval provider; the agent cannot forge the receipt |
| `workspace_revoke_trust` | `workspaceId` | none | Revocation status; invalidates cached grants | Revocation is fail-safe and immediately denies future project-code execution under the grant |
| `app_build` | `workspaceId`, `targetId` | `variant`, `clean`, `offline`, `arguments[]` (allowlisted), `buildReference` | Build ID/reference, artifact IDs, package, debuggable flag, output summary and exit status | `WORKSPACE_EXECUTION`; requires Workspace Trust; project code runs with current OS user unless an actual OS sandbox is active |
| `app_install` | `deviceId`, `artifactId` | `replaceExisting:boolean=false` | Package, selected device, install status and captured output | `DEVICE_MUTATION`; explicit device required |
| `app_launch` | `deviceId`, `packageName` | `activity`, `buildReference`, `waitForDebugger:boolean=false`, `timeoutMs` | Verified process reference including process-instance ID and start token when available | `APP_LAUNCH`; explicit device/package required |

`toolchain_probe` must report `lldb`, `lldb-dap` and `lldb-server` separately. Finding `lldb` does not imply `lldb-dap` is installed or that a target-compatible `lldb-server` exists.

### 2.2 Sessions

| Tool | Required input | Optional input | Result | Side effect |
|---|---|---|---|---|
| `session_start` | `deviceId` | `workspaceId`, `packageName`, `buildTargetId`, `backendIds[]`, `startApp`, `captureLogcat` | Session ID, resolved process instance, backend states, capabilities and subsystem health | Creates session; may launch app and start log collection if requested |
| `session_status` | `sessionId` | `refreshCapabilities:boolean=false` | Session/process state, independent backend lifecycle/inspectability, active suspension leases and subsystem health | `READ_ONLY` |
| `session_stop` | `sessionId` | `stopApplication:boolean=false`, `detachBackends:boolean=true` | Cleanup report; app is not force-stopped unless explicitly requested | Session mutation; app stop is an explicit device mutation |

Session state is not a proxy for backend state. A session can be running while one backend is detached or temporarily unable to inspect a process stopped by another backend.

### 2.3 UI inspection, screenshots and actions

| Tool | Required input | Optional input | Result | Capability / side effect |
|---|---|---|---|---|
| `ui_inspect` | `sessionId` | `full:boolean=true`, `includeRaw:boolean=false` | Normalized live UI hierarchy, generation ID, raw CLI data reference and coverage | `READ_ONLY` |
| `ui_find` | `sessionId`, `selector` | `limit`, `exact:boolean`, `expectedGeneration` | Matching elements, count and hierarchy generation | `READ_ONLY` |
| `ui_act` | `sessionId`, `action` | `selector`, `text`, `direction`, `key`, `expectedGeneration`, `expectedState`, `confirmationToken` | Match set, chosen backend, performed status, post-action verification and resulting UI generation | `UI_ACTION`; `UiActionPolicy` is mandatory; destructive/external/unknown risk requires confirmation |
| `ui_screenshot` | `sessionId` | `annotate:boolean=false`, `format` | Image content plus immutable artifact reference, screen metadata and generation | `READ_ONLY` |

Interaction order: fresh semantic hierarchy → unique selector → policy assessment → action → fresh post-action verification. If selectors fail, visual resolve may only use a screenshot from the current screen generation. Coordinates are a last resort and require a fresh resolved target. Selector uniqueness does not imply that an action is safe.

### 2.4 Logcat

| Tool | Required input | Optional input | Result | Side effect |
|---|---|---|---|---|
| `logcat_start` | `sessionId` | `buffers[]`, `format` (`TEXT`, `BINARY`, `PROTO`), `maxBytes`, `spoolQuotaBytes` | Collector ID, accessible/inaccessible buffers, selected format and initial coverage | Starts background capture |
| `logcat_stop` | `sessionId`, `collectorId` | none | Final capture summary and raw artifact references | Stops collector |
| `logcat_query` | `sessionId` | `from`, `to`, `processInstanceId`, `pid`, `tid`, `priorities[]`, `tag`, `text`, `cursor`, `limit` | Paginated records with raw evidence references | `READ_ONLY` |
| `logcat_statistics` | `sessionId` | `collectorId`, `refresh:boolean=false` | Collector throughput, queue/spool metrics, available logd statistics, buffer sizes and errors | `READ_ONLY` |
| `logcat_health` | `sessionId` | `collectorId` | Separate `CollectorLoss` and `DeviceLogLoss`, coverage status, known gaps and limitations | `READ_ONLY` |

`COMPLETE` may be returned only when the evidence supports it. If logd statistics are disabled/unavailable, device-side historical eviction is `UNVERIFIED`, not zero. Internal collector drops must be exact when measurable.

### 2.5 Java/Kotlin, native and Flutter debugging

| Tool | Required input | Optional input | Result | Capability / side effect |
|---|---|---|---|---|
| `debug_attach` | `sessionId`, `processInstanceId`, `backendId` | `waitForAttach:boolean=false`, `switchBackend:boolean=false` | Backend attach state, process identity, actual capabilities, and sequential-switch details | `DEBUGGER_CONTROL` |
| `debug_detach` | `sessionId`, `backendId` | `processInstanceId` | Detach one backend without terminating the app/session | `DEBUGGER_CONTROL` |
| `debug_status` | `sessionId` | `processInstanceId` | Process execution state, stop epoch, per-backend status, blocked reason and active leases | `READ_ONLY` |
| `debug_breakpoint_add` | `sessionId`, `backendId`, `breakpoint` | `processInstanceId` | Breakpoint ID, verification and resolved locations | `DEBUGGER_CONTROL` |
| `debug_breakpoints_list` | `sessionId`, `backendId` | `processInstanceId` | Current breakpoints and resolution status | `READ_ONLY` |
| `debug_breakpoint_remove` | `sessionId`, `backendId`, `breakpointId` | `processInstanceId` | Removal status | `DEBUGGER_CONTROL` |
| `debug_pause` | `sessionId`, `backendId`, `processInstanceId` | `threadId` | Execution snapshot, stop origin/scope and stop epoch | `DEBUGGER_CONTROL` |
| `debug_continue` | `sessionId`, `backendId`, `processInstanceId` | `threadId`, `allThreads:boolean=true` | Execution snapshot and suspension leases still active | `DEBUGGER_CONTROL`; cannot release another backend's suspension lease |
| `debug_step` | `sessionId`, `backendId`, `processInstanceId`, `threadId`, `kind` | `granularity` | New stop event/snapshot and current stop epoch | `DEBUGGER_CONTROL` |
| `debug_threads` | `sessionId`, `backendId`, `processInstanceId` | `includeStates:boolean=true` | Thread IDs, names and states | `READ_ONLY` |
| `debug_stack` | `sessionId`, `backendId`, `processInstanceId`, `threadId`, `stopEpoch` | `startFrame`, `levels` | Stack frames with epoch-scoped handles and source locations | `READ_ONLY` |
| `debug_variables` | `sessionId`, `backendId`, `frameHandle` | `start`, `count`, `includeChildren:boolean=false` | Locals/scopes/fields with epoch-scoped variable references | `READ_ONLY` |
| `debug_evaluate` | `sessionId`, `backendId`, `frameHandle`, `language`, `expression` | `policyProfile` (default `READ_ONLY`), `confirmationToken` for approved Dart runtime evaluation | Value or typed policy/evaluation error, side-effect classification and epoch | `READ_ONLY` subset for Java/Kotlin; Flutter/Dart evaluation is capability-gated and explicitly classified; arbitrary native evaluation is not exposed in v1 |
| `debug_modules` | `sessionId`, `backendId`, `processInstanceId` | `includeBuildIds:boolean=true` | Loaded modules, paths, ABI, Build IDs and symbol-match status | `READ_ONLY` |
| `debug_registers` | `sessionId`, `backendId`, `threadId`, `stopEpoch` | `registerGroups[]` | Register values with frame/stop context | `READ_ONLY` |
| `debug_memory_read` | `sessionId`, `backendId`, `processInstanceId`, `address`, `size`, `stopEpoch` | `format` | Bounded memory bytes and artifact/reference metadata | `READ_ONLY`; size is capped by policy |

Debugger control operations are serialized per process instance. A backend may release only suspension leases that it owns. If stop ownership or scope cannot be reconciled, return a typed conflict instead of resuming the target.

`debug_evaluate` does not enable arbitrary LLDB expression execution. Native inspection uses DAP scopes/variables and the bounded `debug_memory_read` tool. Method invocation, assignment and arbitrary code remain disabled by default. Any future executable evaluator requires a separately versioned tool/capability and explicit approval.

### 2.6 Profiling, preview and artifact analysis

| Tool | Required input | Optional input | Result | Capability / side effect |
|---|---|---|---|---|
| `profiler_systemtrace_start` | `sessionId`, `durationMs` | `profileConfig`, `bufferSizeKb` | Profiler session ID, configuration and expected overhead | `PROFILING`; explicit opt-in |
| `profiler_systemtrace_stop` | `sessionId`, `profilerSessionId` | none | Perfetto trace artifact and capture report | Stops profiler and stores artifact |
| `profiler_heapdump_capture` | `sessionId`, `processInstanceId` | `format` | JVM HPROF artifact and target/API limitations | `PROFILING`; requires debuggable process and supported API; never label as native heap |
| `performance_trace_analyze` | `artifactId` | `queries[]`, `timeRange` | Trace metrics, findings and source artifact reference | `READ_ONLY` |
| `preview_list` | `workspaceId` | `modulePath`, `filter` | Build-time Compose Preview descriptors | Workspace inspection; if command executes project code, requires trust |
| `preview_render` | `workspaceId`, `previewId` | `deviceProfile`, `theme` | Rendered preview artifact | `WORKSPACE_EXECUTION`; requires trust |
| `analyze_artifact` | `artifactId` or approved `path` | `format` (`json` only in v1), `analysisOptions` | Versioned normalized report plus raw output artifact | `READ_ONLY` for stored artifacts; workspace path access obeys Workspace Trust/path policy |

### 2.7 Diagnostics and artifacts

| Tool | Required input | Optional input | Result | Side effect |
|---|---|---|---|---|
| `diagnostics_timeline` | `sessionId` | `from`, `to`, `types[]`, `cursor`, `limit` | Ordered event page retaining source sequence and clock uncertainty | `READ_ONLY` |
| `diagnostics_crashes` | `sessionId` | `processInstanceId`, `from`, `to` | Crash/ANR records with evidence references and coverage | `READ_ONLY` |
| `diagnostics_analyze` | `sessionId` | `scope`, `from`, `to`, `evidenceIds[]` | Evidence-backed diagnoses, confidence, hypotheses and limitations | `READ_ONLY` |
| `artifact_get` | `sessionId`, `artifactId` | `representation` (`RAW`, `REDACTED_VIEW`, `METADATA`) | Resource link or bounded content, hash and redaction metadata | `READ_ONLY`; enforces session ownership |

### 2.8 Optional integrations and experimental tools

| Tool | Required input | Availability | Side effect |
|---|---|---|---|
| `signals_crashes` | `projectId`, `timeRange` | Only when existing Crashlytics authentication and capability are configured | `CLOUD_READ`; explicit invocation; results labeled field telemetry and never merged with local logcat as if same provenance |
| `compose_inspect` | `sessionId`, `inspectionKind` | Experimental; only when a supported runtime adapter is proven available | `READ_ONLY`; may return structured `UNAVAILABLE` or `EXPERIMENTAL` with evidence |

These tools do not block local debugging startup and do not perform interactive cloud authentication.

## 3. MCP resources

Register these read-oriented resources:

| URI | Content |
|---|---|
| `admcp://catalog/v1` | Exact public tool/resource catalog version, capability and side-effect metadata |
| `admcp://sessions/{sessionId}` | Current session/process/backend states and capabilities |
| `admcp://sessions/{sessionId}/timeline` | Paginated or bounded timeline view |
| `admcp://sessions/{sessionId}/logcat/{collectorId}` | Raw or parsed log stream metadata and query guidance |
| `admcp://sessions/{sessionId}/artifacts/{artifactId}` | Artifact metadata and permitted representation link |
| `admcp://sessions/{sessionId}/capabilities` | Current device/project/process capability snapshot |

All resources enforce session ownership and redaction policy for agent-facing representations. Raw data retrieval must be explicit and separately labeled.

## 4. Startup and contract validation

At startup in strict v1 mode:

1. Load the checked-in `tool-catalog-v1.json` generated from this Markdown contract.
2. Validate unique names, JSON schemas, enum references, side-effect classes and required capability identifiers.
3. Register every non-optional tool with a concrete handler.
4. Register optional tools only when their facade exists; otherwise register a capability-aware handler that returns typed `UNAVAILABLE`, if the catalog marks the tool as optional and exposed.
5. Fail startup if a required catalog entry lacks a handler or a handler is not represented by the catalog.
6. Expose catalog version and capabilities through `admcp://catalog/v1`.
7. Run a contract test comparing `tools/list`, schemas and handler registry with this catalog.

A backend capability may be deferred from v1 only by updating both this catalog and the v1 release acceptance checklist before public release.

## 5. Versioning policy

- Adding a required tool or changing required arguments/result semantics is a catalog-breaking change and requires a new catalog version.
- Optional fields may be added compatibly when they have documented defaults.
- Optional tools must be labeled optional/experimental and return structured unavailability, never disappear silently when the catalog promises their presence.
- Internal backend refactoring does not change the public catalog unless externally observable behavior changes.
- No generic `shell`, `exec`, unrestricted `adb`, or arbitrary command tool is part of catalog v1.

## 6. Milestone exposure

Each tool has a machine-readable `releaseGate` field. The cumulative runtime profiles are:

- **A:** `device_list`, `device_inspect`, `toolchain_probe`, `workspace_open`, `workspace_describe`, `workspace_trust`, `workspace_revoke_trust`, `app_build`, `app_install`, `app_launch`, `session_start`, `session_status`, `session_stop`, `ui_inspect`, `ui_find`, `ui_act`, `ui_screenshot`, `logcat_start`, `logcat_stop`, `logcat_query`, `logcat_statistics`, `logcat_health`, `diagnostics_timeline`, `artifact_get`.
- **B:** all A tools plus `debug_attach`, `debug_detach`, `debug_status`, `debug_breakpoint_add`, `debug_breakpoints_list`, `debug_breakpoint_remove`, `debug_pause`, `debug_continue`, `debug_step`, `debug_threads`, `debug_stack`, `debug_variables`, `debug_evaluate`.
- **C / Full v1:** all A and B tools plus `debug_modules`, `debug_registers`, `debug_memory_read`, `profiler_systemtrace_start`, `profiler_systemtrace_stop`, `profiler_heapdump_capture`, `performance_trace_analyze`, `preview_list`, `preview_render`, `analyze_artifact`, `diagnostics_crashes`, `diagnostics_analyze`, `signals_crashes`, `compose_inspect`.

The full catalog resource describes all planned v1 entries from the start. Runtime `tools/list` is filtered by the configured cumulative release profile; a later-gate tool is never advertised until its concrete handler ships. Full v1 must expose all 51 entries (49 required/core and 2 optional/experimental capability-gated entries).
