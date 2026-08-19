# Browser Capability — Integration Notes (from reading MoatazAlalqamiAI codebase)

## Task (from /home/ubuntu/upload/pasted_content_2.txt)
Add official Browser Capability via architecture:
Agent Runtime → Browser Tools → ApprovalEngine/Security → BrowserEngine abstraction → Lightpanda backend

Requirements:
- commonMain abstraction: BrowserEngine, BrowserSession, BrowserAction, BrowserResult, BrowserPolicy
- Tools: browser.open, browser.read, browser.click, browser.type, browser.back, browser.extract, browser.close
- LLM-friendly output (Markdown/semantic tree), stable element IDs (targetId, no CSS selectors)
- All tools go through ToolRegistry + ApprovalEngine; classify browsing/reading low-risk; form submit, login, credentials, purchase, publish, delete = NetworkWrite approval-gated
- Security: prompt injection from web pages, SSRF (localhost/private/metadata), credential leakage to LLM, session sharing between AgentRuns, schema/tools from page content
- BrowserSession per AgentRun, close on Complete/Failed/Cancelled; cancellation propagation
- No Lightpanda source in Kotlin app; no binary in APK; replaceable backend (BrowserRouter), LightpandaLocalEngine possible later, WebView = fallback/visual preview only
- KMP boundaries (commonMain contracts; androidMain details); use existing DI (no new singletons/service locators)
- Tests: BrowserPolicy, Approval integration, SSRF blocking, session isolation, cancellation, serialization
- Document AGPL implications
- Deliver: architecture summary, file list, decision rationale, done/left TODO, build/tests results, Lightpanda-on-Android risks

## Existing codebase integration points (verified Aug 19, 2026)
- **ToolRuntime** (`composeApp/src/commonMain/.../tools/ToolRuntime.kt`): `suspend fun call(name, raw: Map<String,Any?>): ToolResult` with `when(name)` dispatch; `riskLevelFor(name): ToolRiskLevel`; constructor `(scope, emitActivity)`. Tools registered via hardcoded when-block + extension.
- **availableToolIds()**: extension at `AgentOrchestrator.kt` line ~401 — static list of 23 tools. Browser tools must be ADDED here.
- **ApprovalEngine** (`agents/ApprovalEngine.kt`): `class ApprovalEngine(knownToolIds: () -> Set<String>)`; decide(toolId, risk: ToolRisk, mode, argsJson). ToolRisk sealed: SafeRead/LocalWrite/NetworkWrite/Dangerous.
- **ToolRiskLevel** (ToolDefinitions.kt): READ_ONLY, WORKSPACE_WRITE, PACKAGE_INSTALL, NETWORK, PROCESS_CONTROL, GIT_WRITE, SECRET_ACCESS, DESTRUCTIVE.
- **mapRisk** (Orchestrator): WORKSPACE_WRITE/PACKAGE_INSTALL→LocalWrite; NETWORK/PROCESS_CONTROL→NetworkWrite; GIT_WRITE→NetworkWrite; SECRET_ACCESS/DESTRUCTIVE→Dangerous.
- **Orchestrator cancellation**: CancellationException propagated; run loop exits. Session cleanup should hook into run finish.
- **DI**: AppModule.kt (commonMain, Koin modules), SecretModule.kt, SandboxModule.kt (androidMain). Koin `single {}` style.
- **SSRF existing helper**: `tools/LocalNetworkUrl.kt` — `fun isLocalNetworkUrl(url)` covers LAN/private/link-local; loopback returns false there. Need BrowserPolicy that also blocks loopback + cloud metadata (169.254.169.254) + private IPv4/IPv6 strictly.
- **Ktor client** available (ktor-client-auth dep added); use for gateway HTTP.
- **SandboxBackend abstraction**: `sandbox/backend/` — SandboxBackend interface, SandboxConfig, SandboxCapabilities, ExecRequest/ExecResult, SandboxError (sealed), SandboxInstance.
- **ApprovalAuditLog**: records verdicts with toolId/risk/argsSummary.
- **WorkspacePanel.kt** (chat/composables): tabs Terminal/Files/Activity — add Browser tab there.

## Lightpanda facts (from docs/LIGHTPANDA_RESEARCH.md)
- AGPL-3.0; binary-only use via separate process (CDP ws:9222 or MCP-over-HTTP 9223) = safe, no conveying.
- MCP-HTTP: POST /mcp JSON-RPC 2.0; Mcp-Session-Id header gives per-agent session isolation.
- Nightly binary: lightpanda-aarch64-linux (glibc) → runs in Ubuntu 26.04 PRoot sandbox of our app!
- `--dump markdown` + `--obey-robots`.

## Design decisions
1. `browser/BrowserEngine.kt` (commonMain): interface + BrowserSession(handle) + BrowserAction(action+args) + BrowserResult(sealed) + BrowserPolicy + CdpPageModel(sealed: Html, Markdown, SemanticTree, Snapshot).
2. `browser/BrowserToolRuntime.kt`: browser tool call handler (browser.* names) wired into ToolRuntime.call — extend with open delegate list OR add BrowserDispatcher field. Choose: add optional `BrowserDispatcher` to ToolRuntime ctor (backward compat) + browser.tools to availableToolIds().
3. `BrowserTools.kt`: 7 tools + risk mapping (read/open/extract→READ_ONLY-like; click/type→WORKSPACE_WRITE; back/close→SAFE... map properly).
4. `SsrfGuard.kt`: blocks loopback, 127.0.0.0/8, 10/8, 172.16/12, 192.168/16, 169.254/16, ::1, fc/fd, fe80::; scheme allowlist http/https only.
5. `PromptInjectionFilter.kt`: strip <script>, on* attrs, meta-refresh, remove invisible instructions in extracted text; never execute schemas/tools from page.
6. `LightpandaCdpGatewayBackend.kt` (androidMain): connects to gateway URL (Lightpanda MCP/CDP endpoint via configured base URL), session per run, Mcp-Session-Id isolation.
7. `MockBrowserEngine` (commonTest): in-memory fake page model for tests.
8. `BrowserRouter.kt`: registry of BrowserEngine per id (local-lightpanda, remote-gateway, mock).
9. Session store in BrowserEngine layer keyed by runId; cleanup on finishRun.
10. Content limit: markdown/semantic tree ≤ 16KB to LLM; targetId = stable int index.
11. UI: BrowserActivityCard in TerminalPanel/WorkspacePanel area showing url/action/status.
12. Settings: browser gateway base URL in ServicesSettings or SandboxSettings.

## Version
- appVersion currently 3.5.0 → bump to 3.6.0 for this feature.
- Branch: feature/ai-gateway-hardening. Releases: v3.5.0 latest published.
- Build commands / keystore / test count script: see TASK_STATE_AGENTHUB.md.
