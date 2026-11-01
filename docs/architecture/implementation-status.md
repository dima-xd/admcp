# Implementation status - v1

Date: 2026-11-01
Design: `docs/architecture/technical-design.md`

## Verified

| Area | Evidence |
|---|---|
| Build and unit suite | `gradlew.bat clean test installDist` passes: 186 tests, 0 failures, 0 skipped on the verified host |
| Live integration suite | 9 of 9 `RealDeviceIntegrationTest` cases pass on the connected phone |
| MCP handshake | `initialize` + `tools/list` over stdio returns 51 tools |
| Device discovery | OnePlus CPH2747, API 36, arm64-v8a |
| Session round-trip | `session_start` -> `session_status` -> `session_stop` |
| Logcat | Query returns parsed threadtime records on the connected device |
| Screenshot | `exec-out screencap -p` stored and read back from the artifact store |
| Workspace | This repository is detected as `ANDROID_GRADLE` |
| UI hierarchy | Repeated live `ui_inspect` calls return a hierarchy (10-119 elements) without the prior `UiAutomationService already registered` failure |
| UI selector action | Live `ui_find` matched the sample button; `ui_act` TAP returned `performed=true`, `verified=true`, and changed the UI from `counter=0` to `counter=1` |
| JDI attach | Attached to a debuggable sample launched with `am start -D` through ADB/JDWP forwarding |
| JDI breakpoint | Source breakpoint on `MainActivity.increment` line 38 verified and hit; stop epoch 1, scope THREAD |
| JDI stack | 15 frames returned; top frame `increment` at line 38 |
| JDI locals | Local `value=0` of type `int` read from the stopped frame |
| JDI step | `STEP_INTO`/line step advanced to epoch 2, line 39, with locals `value=0` and `next=1` |
| JDI cleanup | `debug_detach` and `session_stop` complete without terminating the session path |
| Perfetto | `profiler_systemtrace_start` + `profiler_systemtrace_stop` captured a 1-second trace and stored a 2,196,837-byte `PERFETTO_TRACE` artifact |
| Crash buffer | `diagnostics_crashes` returned 200 threadtime records from `logcat -b crash` and stored a text artifact |
| NDK LLDB discovery | Toolchain probe resolves LLVM 20.1.8 `lldb-dap`, LLVM `lldb`, and NDK 29 aarch64 `lldb-server` with `LLVM_HOME` set |
| Native LLDB attach | MCP `debug_attach` with `backendId=LLDB` attached to an arm64 JNI fixture through `run-as` gdbserver and returned `attached=true` |
| Native breakpoint | `fixture_increment` function breakpoint verified; after continue, stop epoch 1 had reason `BREAKPOINT`; `debug_step` produced epoch 2 with reason `STEP` |
| Native breakpoint removal | `debug_breakpoint_remove` completed and detached cleanly |
| Native stack and JNI | Stack returned `fixture_increment` and `Java_dev_admcp_nativefixture_MainActivity_nativeIncrement`, proving the JNI transition frame |
| Native source mapping | Live stack frames now include `SOURCE=<workspace>/native/fixture.c` and `LINE=7`/`LINE=16` from frame-level DAP fields |
| Native locals and registers | Locals `value=0`, `next=0`, `fixture_counter=0`; arm64 registers included `x0`-`x7`, `fp`, `lr`, `sp`, `pc` |
| Native memory and modules | A 32-byte bounded stack read succeeded; `debug_modules` returned 437 entries merging the app `libfixture.so` with system mappings from `/proc/<pid>/maps` |
| Native pause | `debug_pause` returned reason `PAUSE_REQUEST`, suspended thread state, and a 3-frame stack after the gdbserver interrupt fallback |
| Native breakpoint listing | Live MCP check: `debug_breakpoints_list` for LLDB returned one verified breakpoint after `fixture_increment` was armed |
| Flutter breakpoint listing | Live MCP check: `debug_breakpoints_list` for `DART_DAP` returned the `lib/main.dart:66` breakpoint with a stable id and cleared the pending-resolution message after the hit |
| JDI breakpoint listing | Live MCP check: `debug_breakpoints_list` for JDI returned one verified source breakpoint with `MainActivity.java:38` |
| Sequential JDI/LLDB switch | Live MCP check: `debug_attach` with `switchBackend=true` moved JDI -> LLDB -> JDI on the same pid three consecutive times; each response reported the detached backend, and the app stayed alive |
| Android CLI update | `android update` installed `1.1.16717390`; `layout --device` returned a 125,121-byte hierarchy and `screen capture --device` wrote a 511,197-byte PNG |
| Android CLI 1.1 families | Capability probe covers `auth`, `create`, `docs`, `emulator`, `info`, `init`, `sdk`, `skills`, `studio` and `device` in addition to the original families |
| Android CLI subcommand routing | `screen capture --help` and `profiler systemtrace start --help` expose `--device`; the probe now checks those subcommands and records `EXPLICIT_DEVICE_REQUIRED` for `screen`/`profiler` instead of `UNVERIFIED` |
| Flutter DAP backend | `DART_DAP` attached through `flutter debug-adapter --no-dds -d <device>`, armed a breakpoint at `lib/main.dart:66`, and hit it with stop epoch 1 |
| Flutter DAP stack and variables | A live hit returned 19 frames with `SOURCE`/`LINE`, the `this` variable for `_MyHomePageState`, and a successful step to epoch 2 |
| Flutter DAP pause and detach | `debug_pause` produced a stopped state at epoch 2; `debug_detach` completed without leaving the adapter running |
| Runtime Compose inspection | A Compose fixture (`dev.admcp.composefixture`) was installed and `compose_inspect` reported `supported=true`, `inspectionKind=TREE`, `source=UI_AUTOMATOR_ACCESSIBILITY` with semantics `COMPOSE COUNTER`, `counter=0`, `COMPOSE INCREMENT`; `RECOMPOSITION` returned structured `supported=false` |
| Destructive host approval | A `DELETE COUNTER` Compose action opened the host confirmation dialog; approval performed and verified the action (`counter=99`) |
| Destructive token fallback | With `-Djava.awt.headless=true`, the same action returned a one-shot token with `hostConfirmation=UNAVAILABLE`; replaying the token performed and verified the action |
| Local crash signals | `signals_crashes` returned `source=LOCAL_DEVICE`, `availability=LOCAL_ONLY`, and 5 device crash-buffer records when Crashlytics credentials were absent |
| Event store | SQLite WAL reopen, filters and stable cursor pagination pass |
| Suspension coordinator | Stop epochs, leases, dual-backend blocking, new same-backend stop epochs and detach pass |
| Session orchestration | Live `session_start` launched the sample, attached JDI, started `main/system/crash` logcat and returned no warnings |
| App build report | Live `app_build` on the Compose fixture returned Gradle output, APK path, package, `debuggable=true`, aapt2 badging and artifact ID |
| Artifact analysis | Live `analyze_artifact` returned Android CLI optimization data plus ZIP/aapt2 normalized package, SDK, native library and debuggability metadata |
| Preview listing | Live `preview list --format=json :app` found `ComposeFixturePreview` |
| Preview render | Live `preview_render` stored a 39,868-byte PNG and a 34,801-byte component tree; `cliSuccess=true` |
| Heap dump | Live `profiler_heapdump_capture` stored a 58.9 MB HPROF and removed the device copy |
| Trace analysis | Live `performance_trace_analyze` accepted a persisted 3-second trace: 168 packets, 9,542 ftrace events, 175 processes, 686 threads, no parse errors |
| Diagnostics timeline | Live `diagnostics_timeline` returned SQLite-backed session events; debug, heap and trace events are appended as the session runs |
| Diagnostics analysis | Live session-scoped `diagnostics_analyze` returned availability, evidence and no false findings without `rawJson` |
| Read-only evaluation | Live JDI frame evaluation returned `instance of android.os.MessageQueue(id=43112)` with `sideEffectsPossible=false` |
| Deferred JDI breakpoint | A breakpoint set before class load returned pending, then `debug_breakpoints_list` returned `verified=true` after `MainActivity` loaded |
| Durable artifact index | Content-addressed artifacts persist a sidecar metadata record and reload after server restart; covered by unit tests |

## Limited by the host, device or credentials

- Native LLDB needs a separately installed `lldb-dap` with its Python runtime.
  The verified host used LLVM 20.1.8 at `<LLVM_HOME>` and NDK 29
  `lldb-server`. Without these, `debug_attach` returns a typed toolchain error.
- Simultaneous JDI + LLDB attach is not reliable on the test device under a
  normal launch: with JDI first, JDI reports `VMDisconnectedException` after
  LLDB attaches; with LLDB first, JDI attach fails with `IOException`. Native
  JNI transition symbolization is verified, but dual-backend inspection is not
  claimed. Sequential switching is supported through
  `debug_attach(switchBackend=true)`, which detaches the previous backend,
  leaves the app running, and attaches the next one.
- Android CLI is `1.1.16717390`; `layout` and `screen capture` are verified.
  Subcommand-level routing for `screen`/`profiler` is now probed and tested.
  A generic Android CLI MCP tool is intentionally absent because the v1 catalog
  forbids arbitrary command tools; existing MCP operations keep using ADB,
  uiautomator, LLDB/JDI, Flutter DAP and Perfetto. The Android CLI
  modules are host-side capability/routing support, not a second public API.
- Field crash intelligence still requires Crashlytics authentication. `android
  signals context` returns `Not logged in. Please run 'android auth login'
  first.`; the local device crash-buffer fallback is verified, but hosted crash
  clusters, trends and issue metadata are unavailable.
- Simultaneous JDI and LLDB inspection remains blocked by ART/JDWP behavior on
  the CPH2747 API 36 device. Without `switchBackend=true`, attaching a second
  backend returns `DUAL_BACKEND_CONFLICT`; with the flag, the previous backend
  is detached first and the requested backend attaches sequentially.
- Runtime Compose inspection is based on the uiautomator hierarchy and reported
  Compose semantics, not a connection to the app's internal Compose semantics
  tree. `TREE` reports `source=UI_AUTOMATOR_ACCESSIBILITY` and explicit
  limitations; `RECOMPOSITION` and `TRACE` return structured unsupported
  results because slot-table and recomposition data need runtime
  instrumentation.
- The host confirmation dialog is desktop Swing UI. On a headless server the
  tool fails closed and returns the one-shot token instead.

## Deliberate safety behavior

- Device-targeted tools accept either an explicit `deviceId` or a `sessionId`
  that resolves to the owning session device. Session-scoped artifact reads
  validate the session when one is supplied.
- Destructive UI actions require host approval in a desktop confirmation dialog.
  When no dialog is available (headless), the tool fails closed and requires a
  second call with the returned one-shot token; both paths are bound to the
  session and action and expire after five minutes.
- `workspace_trust` succeeds only for a canonical root passed to the server at
  startup with `--trust-workspace`; tool arguments cannot self-assert approval.
  Without that operator flag it fails closed.
- Expression evaluation is read-only and method invocation is disabled in v1.
- Logcat never issues clear or resize commands.
- Artifact paths are confined to the store root; reads are capped, binary
  content is returned as base64, and text is redacted by default unless the
  caller explicitly requests the raw representation.
- Diagnostics returns availability, evidence and confidence; AI summaries are
  marked as hypotheses.

## Tool coverage

All 51 catalog tools are registered with their declared input schemas. Implemented
v1 paths cover device, workspace, app lifecycle gates, session, UI, screenshot,
logcat, JDI and LLDB debug, Perfetto, artifact and diagnostics calls. Tools whose
backend is absent return a structured non-success result rather than an empty
success.

Residual limits after closing the v1 tool gaps:

- `performance_trace_analyze` is a bounded pure-Kotlin protobuf summary. It
  validates packet framing, extracts timestamps, ftrace event counts, process
  and thread tables and supports a time range. It does not provide SQL slices,
  frame lifecycle or scheduler analysis because no host
  `trace_processor_shell` is bundled; `TOP_SLICES` can be empty for ordinary
  system traces.
- `preview_render` delegates to Android CLI. A Compose project must expose the
  preview runtime classes (the verified fixture needed
  `androidx.compose.ui:ui-tooling`); otherwise files can be absent and the CLI
  error is returned.
- `compose_inspect` reads the accessibility semantic tree. App-internal
  semantics, slot-table and recomposition data still need runtime
  instrumentation.
- Hosted Crashlytics clusters need `android auth login`; the local crash buffer
  is the default fallback.
- Simultaneous JDI and LLDB inspection remains blocked by ART/JDWP on the test
  device; use sequential `switchBackend=true`.
