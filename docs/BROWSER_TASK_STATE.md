# Browser Capability — Task State (Aug 19, 2026)

## Task source
/home/ubuntu/upload/pasted_content_2.txt — requirements: BrowserEngine abstraction (commonMain), BrowserSession/Action/Result/Policy, 7 tools (browser.open/read/click/type/back/extract/close), SSRF/prompt-injection/session-isolation/cancellation protection, Lightpanda backend reference (no binary in APK), MockEngine for tests, DI via existing Koin, tests (policy, approval, SSRF, isolation, cancellation, serialization), AGPL docs, APK v3.6.0 release.

## Completed files
- composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/browser/BrowserEngine.kt (BrowserEngineId, BrowserSession, CdpElement, CdpPageModel, CdpNode, BrowserAction, ReadFormat, BrowserResult sealed, PromptInjectionFilter, SsrfGuard — recently fixed parseHost for bare IPv6 + ipv6Blocked, BrowserRouter, BrowserSessionManager with run()/finishRun())
- composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/browser/BrowserPolicy.kt (MAX_LLM_CONTENT_CHARS=16K, MAX_TYPE_CHARS=4K, validateOpen/Target/Type/Extract, capForLlm, DENY_LIST)
- composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/BrowserTools.kt (BrowserDispatcher(sessions, runtime), 7 typed args classes, risk mapping, cleanupRun(runId), describePageModel)
- composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/tools/ToolRuntime.kt edits: browserDispatcher field + emitBrowserActivity internal + dispatch branch + riskLevelFor entries
- composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/agents/AgentOrchestrator.kt edits: browser tools in availableToolIds() + finishRun calls dispatcher.cleanupRun(runId)
- composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/browser/LightpandaGatewayBackend.kt (OkHttp engine, MCP-HTTP JSON-RPC, Mcp-Session-Id header, config, retryable errors)
- composeApp/src/commonTest/kotlin/com/inspiredandroid/kai/browser/MockBrowserEngine.kt + BrowserPolicyTest.kt (20 tests) + BrowserDispatcherTest.kt (9 tests)

## Remaining failures to fix (5 tests failing)
1. BrowserPolicyTest `ssrf blocks cloud metadata and link-local` — FIXED (SsrfGuard parseHost for bare IPv6). Re-run.
2. BrowserDispatcherTest `open reads back and closes through the runtime` — open returns Failure? investigate: runTest uses TestDispatcher; ToolRuntime.scope is IO Dispatcher (may block in runTest), or browserOpen → sessions.run needs runId entry — sessions.sessionFor uses defaultEngine which needs router.engineFor... Check error message.
3. `click and type require valid target ids` — badClick should fail with "Blocked by browser policy" — failing means isFailureLike false or message mismatch.
4. `browser activity is emitted into the timeline` — events list empty; browserOpen may be returning Failure (emit happens only on success path in toToolResult? No—Failure also emits via emitBrowserActivity in catch... check path).
5. `back fails without history` — similar.

## Debug hint
The failures may be because runTest's testCoroutineScheduler doesn't execute ToolRuntime.scope (SupervisorJob + Dispatchers.IO — uses IO dispatcher, not injected). In runTest, Dispatchers.IO is replaced by TestDispatcher, so it SHOULD work. More likely: browserOpen calls sessions.sessionFor which calls router.defaultEngine().openSession (mock) — fine. Then sessions.run(action=Open(...)) — fine. Hmm — actually check ToolResult.Success.isSuccessLike: ToolResult.Success is a MEMBER data class, extension property isSuccessLike with receiver `com.inspiredandroid.kai.tools.ToolResult` should NOT conflict. But wait: `assertTrue(open.isSuccessLike)` — open is ToolResult. OK.

Suspect real cause: runtime.call uses toolRuntime.availableToolIds() which now includes browser tools but browserDispatcher is set on same runtime instance — OK. The catch in Dispatcher.call catches Exception and emits activity then returns Failure — for open with valid url shouldn't happen.

NEXT: re-run tests after SsrfGuard fix; add println for first failing test if still failing.

## Build commands
```
cd /home/ubuntu/MoatazAlalqamiAI
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :composeApp:compileAndroidMain
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :composeApp:testAndroidHostTest --tests "com.inspiredandroid.kai.browser.*"
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :composeApp:testAndroid   # full suite
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :androidApp:assembleFossRelease -Pandroid.injected.signing.store.file=/home/ubuntu/release-apks/release.keystore -Pandroid.injected.signing.store.password=moataz2024 -Pandroid.injected.signing.key.alias=moataz-ai -Pandroid.injected.signing.key.password=moataz2024
```
Test count script: /home/ubuntu/count_tests.py (reads XML in composeApp/build/test-results/testAndroidHostTest/)
GitHub: Mtzallqmy/MoatazAlalqamiAI branch feature/ai-gateway-hardening, token in env. Version bump: libs.versions.toml appVersion → 3.6.0.

## Bug report (Aug 19, from user screenshots)
EISDIR during Linux install (Ubuntu rootfs extraction):
`Error: /data/user/0/com.inspiredandroid.kai/files/linux-sandbox/rootfs/../usr/share/nodejs/@babel/plugin-bugfix-safari-id-destructuring-collision-in-function-expression/lib: open failed: EISDIR (Is a directory)`
Root cause analysis (TarExtractor.kt androidMain): when tar header typeFlag is not recognized (e.g. GNU long-name '././@LongLink' type 'L'/'K', or PAX 'x'/'g'), the else branch does NOT skip the entry's block-aligned data for TYPE_REGULAR paths — actually it does skip at bottom for size>0. REAL BUG: when a DIRECTORY entry appears AFTER a regular-file path with the same name, or when two consecutive entries for same path where the FIRST is a directory header but the header's typeFlag '0' with size>0 while the path already exists as a DIRECTORY → FileOutputStream(outFile) throws EISDIR.
Likely scenario in LXC rootfs: a dir named "…/lib" appears as type '5' later, but an entry with name "…/lib" and typeFlag '0' with size=0 (or a hardlink '1') appears — hardlink '1' with existing dir target uses File(targetDir,linkName) path but outFile itself may be dir → copyTo fails? No, EISDIR is from FileOutputStream open.
Conclusion: the tar has a regular-file header whose path currently EXISTS AS A DIRECTORY on disk (tar ordering issue or duplicate entry for same name). Also longname 'L'/'K' entries are not handled: name '././@LongLink' with content following = actual name of next entry → the extractor writes data to wrong path? It skips via bottom path (size>0 skip), fine.
FIX: before opening FileOutputStream for regular entry: if outFile.exists() && outFile.isDirectory, delete it first (outFile.deleteRecursively() — careful with proot but these are extracted dirs). Also after extracting a regular file, if an existing file blocks mkdirs for later dir, handle.
Error path shows "rootfs/..usr/share/nodejs/…/lib" → the parent directory "lib" already created as directory, then a regular entry named "…/lib" (symlink target named lib? or tar entry for file named same) triggers.

## User request 2 (same message)
Add file upload/attachment support of ALL formats (images, PDF, etc.) so the agent/models can read, analyze, review, edit them in chat or terminal.
