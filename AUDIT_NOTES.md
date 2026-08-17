# Kai Project Audit Notes (Internal)

## Project Inventory
- Monorepo KMP: composeApp (shared lib) + androidApp (Android host) + iosApp/desktop/wasm
- ~402 Kotlin files in composeApp, ~1.1K lines total in core data files
- License: Apache 2.0 (LICENSE.txt), THIRD_PARTY_LICENSES.md exists (proot GPL-2, talloc LGPL-3)
- Original app name: "Kai 9000", package com.inspiredandroid.kai, version 3.0.0 / code 114
- AGP 9.3.1 (very new), Kotlin 2.4.10, Compose MP 1.11.1, Ktor 3.5.2, Koin 4.2.2, Navigation Compose 2.9.2, SQLDelight 2.3.2, LiteRT 0.16.0, minSdk 26, target/compile 37, JVM target 21
- Needs JDK 21 (sandbox has 21.0.11 OK)

## Current architecture
- App.kt: single NavHost with Home/Settings routes + ChatScreen
- Koin DI (AppModule), Settings (multiplatform-settings) JSON-based persistence
- SQLDelight: single conversation.sq (KaiDatabase)
- Service.kt: 30+ sealed providers (Free/OpenAI/Gemini/Anthropic/OpenAICompatible/Ollama/LiteRT...) with static catalog
- ServiceInstance: unlimited configured service instances supported already (KEY_CONFIGURED_SERVICES)
- ModelCatalog.kt: 1956 lines static model catalog; ModelDefinition simple
- Network: Ktor; providers: AnthropicMessages.kt, OpenAIMessages.kt; DTOs for anthropic/gemini/openai-compatible
- MCP: McpClient/ServerConfig/ServerManager, PopularMcpServers, SSE/streamable support
- Linux sandbox: ProotLauncher, LinuxInstaller, PersistentSandboxShell, SandboxFiles (already strong)
- Local inference: LiteRT (LocalInferenceEngine, ModelDownloadService)
- Tools: AgentToolSet, FetchUrlTool, WebSearchTool, CommonTools, SchedulingTools etc. Tool calling supported
- Memory: MemoryStore (agent_memories JSON in Settings)
- Tasks: ScheduledTask + TaskScheduler + CronExpression + DaemonService + DaemonController
- UI: chat (Compose), settings sections, sandbox screens, dynamic UI (KaiUi)
- Icons/assets under site/, screenshots/, fastlane/ (huge 274M metadata+screenshots)
- GitHub Actions exist in .github/workflows

## What's already there vs prompt
Already: multiple providers/instances, base URL/API key per service, model discovery, LiteRT local models, Linux sandbox with git/terminal, MCP client, scheduled tasks/daemon, tool calling with approvals-ish, streaming
Missing mostly: agent runtime/orchestrator (supervisor, runs, timeline), projects workspace, approval engine typed, model router with profiles, usage/cost tracking, provider health, GitHub integration screen, Telegram, security: API keys currently in Settings (plaintext JSON! needs encrypted storage), design system/components, bottom nav tabs (Home/Chat/Projects/Agents/Activity), Arabic RTL polish, tests expansion

## Identity plan
- New name: "Moataz Alalqami AI" (brand constant file AppBrand.kt)
- Repo: moataz-alalqami (token: github_pat_11BRV7KFI0...); if taken → moataz-alalqami-ai-agent
- Namespace migration: risky (proot native libs, KMP targets, iOS). Keep package for build safety? Prompt allows safe change. Decision: keep com.inspiredandroid.kai internal package but change app_name/display/icon/theme/about, plus LEGAL_COMPLIANCE.md + attribution. Actually prompt says change namespace if safe — but iOS/desktop targets, jniLibs, deep links would break. Keep package, rebrand externally.
- Icons: generate new adaptive icons (vector) in androidApp/src/main/res
- Strings: add brand resources

## Execution plan (practical phases)
P0: audit + clean git history start
P1: branding (AppBrand.kt, strings, icons, about, remove fastlane/screenshots/site from new repo), design system tokens + components
P2: secret store (EncryptedPrefs/Keystore-backed SecretStore), provider health, URL normalization improvements, usage tracking lite
P3: model router + profiles (core-domain), task classifier
P4: projects entity + project screen tabs (Files/Terminal/Git already exist in sandbox)
P5: agent runtime (AgentCore: supervisor, run/steps, timeline, approval engine, handoffs)
P6: GitHub integration (octocat API client + screen), Telegram (bot polling + allowlist)
P7: docs (README, ARCHITECTURE, SECURITY, PRIVACY, LEGAL_COMPLIANCE, MIGRATION, ROADMAP, CONTRIBUTING), CI workflow, tests (routing, url normalization, permissions, cost)
- keep build green; android debug build if environment permits (no Android SDK? check sdkmanager availability; may need to install cmdline-tools + platform-tools + platform 37 + JDK)

## Environment constraints
- Sandbox: no Android SDK installed yet; may install via cmdline-tools to build APK debug (large download ~2-4GB, memory OK, disk OK)
- Token given for GitHub uploads; repo creation private default
