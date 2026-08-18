# Target Architecture — Agentic Development Platform

> Final target architecture for **Moataz Alalqami AI Agent** (v3.4.0+)
> Supersedes: `docs/ARCHITECTURE.md` (which described the v3.x baseline)

---

## 1. Overview

Moataz Alalqami AI Agent is an **Agentic Development Platform** that runs AI agents capable of executing multi-step software development tasks in a sandboxed environment. The platform supports two sandbox backends behind a unified `SandboxBackend` interface:

| Backend | Environment | Purpose |
|---|---|---|
| `LocalProotSandboxBackend` | Ubuntu 26.04 LTS via PRoot on-device | Offline development, light tasks |
| `RemoteSandboxBackend` | Ubuntu 26.04 VM via Incus on server | Heavy builds, long-running servers, cross-platform |

The agent does not know which backend it is using — both expose the same API.

---

## 2. High-Level Architecture

```
                         Moataz Alalqami AI
                                 │
                                 ▼
                      Agent Orchestrator
                                 │
                   ┌─────────────┴─────────────┐
                   │                           │
                   ▼                           ▼
              AI Gateway                  Tool Runtime
                   │                           │
       ┌───────────┼────────────┐              │
       │           │            │              ▼
     Direct    OpenRouter    LiteLLM      SandboxBackend
       │           │            │              │
       │           │            │     ┌────────┴─────────┐
       │           │            │     │                  │
       └───────────┴────────────┘     ▼                  ▼
                   │             Local Ubuntu       RemoteSandbox
                   │                 PRoot             Backend
                   │                  │                  │
              Local Models       Android          HTTPS/WebSocket
                                                       │
                                                       ▼
                                               Sandbox Gateway
                                                       │
                                                       ▼
                                                  SandboxProvider
                                                       │
                                                       ▼
                                                    Incus
                                                       │
                                                       ▼
                                              Ubuntu 26.04 VM
```

---

## 3. Component Inventory

### 3.1 Agent Orchestrator

Manages the full task cycle:

```
User Request → Task Classification → Model Routing → LLM
    → Tool Call → Approval/Policy → Sandbox Execution
    → Observation → LLM → Next Action → Validation → Final Result
```

**Key properties:**
- Multi-step task execution
- Tool calling with typed arguments and results
- Automatic retries on transient failures
- Cancellation (user-initiated)
- Streaming responses
- Approval engine with risk levels
- Budgets: max steps, max runtime, max cost
- Activity logging (timeline view)
- Failure recovery
- No infinite loops (hard stop at max steps)

**Files:** `agents/AgentRuntime.kt`, `agents/AgentRunExecutor.kt`, `agents/ApprovalEngine.kt`

---

### 3.2 AI Gateway

Routes requests to the appropriate provider:

| Route | Protocol | Use Case |
|---|---|---|
| Direct | OpenAI / Anthropic / Gemini / Ollama | Direct API calls |
| OpenRouter | OpenAI-compatible | Multi-provider routing with metadata |
| LiteLLM | OpenAI-compatible | Self-hosted or third-party proxy |
| Local | MLC / ONNX runtime | On-device inference |

**Components:**
- `TaskClassifier`: heuristic-based task classification (no LLM)
- `ModelRouter`: weighted selection with routing profiles
- `FallbackStrategy`: ordered fallback chain per task type
- `ProviderHealthRegistry`: per-instance health tracking
- `UsageRecorder`: token/cost tracking with budget enforcement
- `UrlNormalization`: protocol adapter per provider
- `ModelMetadata`: capability catalog (vision, tools, reasoning, pricing)

**Files:** `gateway/*.kt`

---

### 3.3 Tool Runtime

Executes tools with full lifecycle management:

**Tools:**

| Category | Tools |
|---|---|
| Terminal | `terminal.exec`, `terminal.exec_stream`, `terminal.input`, `terminal.cancel` |
| Filesystem | `fs.list`, `fs.read`, `fs.write`, `fs.patch`, `fs.move`, `fs.delete`, `fs.search` |
| Git | `git.status`, `git.diff`, `git.log`, `git.branch`, `git.checkout`, `git.commit` |
| Process | `process.list`, `process.kill` |
| Network | `port.open`, `port.close` |
| Sandbox | `sandbox.info`, `sandbox.snapshot`, `preview.open` |

**Each tool has:**
- Typed arguments (Kotlin data class)
- Typed result (Kotlin sealed class)
- Timeout enforcement
- Risk level classification
- Cancellation support
- Error mapping to unified error model
- Activity event emission

**Files:** `tools/ToolRuntime.kt`, `tools/ToolDefinitions.kt`

---

### 3.4 SandboxBackend Abstraction

```kotlin
interface SandboxBackend {
    val capabilities: SandboxCapabilities
    suspend fun create(config: SandboxConfig): SandboxInstance
    suspend fun start(id: String)
    suspend fun stop(id: String)
    suspend fun destroy(id: String)
    suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult
    suspend fun execStreaming(sandboxId: String, request: ExecRequest, listener: ExecStreamListener): CommandHandle
    suspend fun listFiles(sandboxId: String, path: String, recursive: Boolean = false): List<SandboxFile>
    suspend fun readFile(sandboxId: String, path: String, maxLength: Int = 64 * 1024): ByteArray
    suspend fun writeFile(sandboxId: String, path: String, content: ByteArray)
    suspend fun deleteFile(sandboxId: String, path: String)
    suspend fun moveFile(sandboxId: String, from: String, to: String)
    suspend fun listProcesses(sandboxId: String): List<SandboxProcess>
    suspend fun killProcess(sandboxId: String, pid: Long, signal: String = "SIGTERM")
    suspend fun openPort(sandboxId: String, port: Int, protocol: String = "tcp"): ExposedPort
    suspend fun closePort(sandboxId: String, port: Int)
    suspend fun snapshot(sandboxId: String, label: String): SandboxSnapshot
}
```

**SandboxConfig:**
```kotlin
data class SandboxConfig(
    val distro: LinuxDistro = LinuxDistro.UBUNTU,
    val resourceProfile: ResourceProfile = ResourceProfile.STANDARD,
    val networkPolicy: NetworkPolicy = NetworkPolicy.DEVELOPER,
    val workspaceRoot: String = "/workspace",
    val maxLifetime: Duration? = null,
    val idleTimeout: Duration? = null
)
```

**Resource Profiles:**
| Profile | vCPU | RAM | Disk |
|---|---|---|---|
| Light | 1 | 2 GB | 10 GB |
| Standard | 2 | 4 GB | 25 GB |
| Build | 4 | 8 GB | 50 GB |

**Network Policies:**
| Policy | Outbound | Inbound |
|---|---|---|
| Offline | Blocked | Blocked |
| Restricted | GitHub/npm/pypi only | Blocked (ports via proxy) |
| Developer | Allowed | Blocked (ports via proxy) |
| Custom | User-defined | User-defined |

---

### 3.5 LocalProotSandboxBackend

Wraps existing components without parallel implementation:

| Existing Component | New Role |
|---|---|
| `LinuxSandboxManager` | Lifecycle management |
| `SandboxController` | Session management |
| `PersistentSandboxShell` | Terminal sessions |
| `SessionShell` | Command execution |
| `ProotLauncher` | PRoot process management |
| `BuildEnvironmentManager` | Agent binary installation |
| `BuildProotExecutor` | Command execution in build context |
| `SandboxFiles` | File operations |
| `LinuxInstaller` | Rootfs extraction/installation |

**Supported distros:** Ubuntu 26.04 LTS (default), Debian 12 (legacy), Alpine 3.19 (legacy)

---

### 3.6 RemoteSandboxBackend

HTTP/WebSocket client that talks exclusively to **Sandbox Gateway Service**:

```
Android App ──HTTPS──▶ Sandbox Gateway ──Incus API──▶ Ubuntu VM
              │
              ├── WebSocket: terminal streaming, preview proxy
              └── REST: exec, files, ports, snapshots
```

**Security properties:**
- No direct Incus access from Android
- Short-lived JWT tokens
- Scoped credentials per session
- No admin credentials on phone
- TLS only

---

### 3.7 Sandbox Gateway Service (Ktor)

Standalone Kotlin service (separate module: `sandbox-service/`):

**Endpoints:**
```
POST   /api/v1/auth/login
POST   /api/v1/auth/refresh
POST   /api/v1/sandboxes          → Create
GET    /api/v1/sandboxes/{id}     → Info
DELETE /api/v1/sandboxes/{id}     → Destroy
POST   /api/v1/sandboxes/{id}/exec          → Execute command
GET    /api/v1/sandboxes/{id}/exec/{cmdId}  → Streaming (WebSocket upgrade)
POST   /api/v1/sandboxes/{id}/exec/{cmdId}/input  → Terminal input
POST   /api/v1/sandboxes/{id}/exec/{cmdId}/cancel → Cancel
GET    /api/v1/sandboxes/{id}/files   → List directory
GET    /api/v1/sandboxes/{id}/files/{path}  → Read file
PUT    /api/v1/sandboxes/{id}/files/{path}  → Write file
DELETE /api/v1/sandboxes/{id}/files/{path} → Delete file
POST   /api/v1/sandboxes/{id}/files/move    → Move/rename
GET    /api/v1/sandboxes/{id}/processes     → List
POST   /api/v1/sandboxes/{id}/processes/{pid}/kill  → Kill
POST   /api/v1/sandboxes/{id}/ports/open    → Expose port
DELETE /api/v1/sandboxes/{id}/ports/{port} → Close port
GET    /api/v1/sandboxes/{id}/snapshots     → List
POST   /api/v1/sandboxes/{id}/snapshots     → Create
WS     /api/v1/sandboxes/{id}/terminal/{shellId}  → Interactive terminal
WS     /api/v1/previews/{previewId}               → Port preview proxy
```

**Features:**
- JWT authentication + refresh tokens
- Role-based authorization (owner, collaborator, viewer)
- Quota enforcement (per-user sandbox count, CPU, RAM, disk)
- Policy enforcement (network, port, process limits)
- Audit logging (all operations with timestamps)
- Health checks for provider

---

### 3.8 Incus VM Provider

```kotlin
interface SandboxProvider {
    val name: String
    val capabilities: SandboxCapabilities
    suspend fun createInstance(config: ProviderInstanceConfig): String
    suspend fun startInstance(id: String)
    suspend fun stopInstance(id: String)
    suspend fun destroyInstance(id: String)
    suspend fun execInInstance(id: String, command: List<String>, timeout: Duration): ExecResult
    suspend fun getNetworkInfo(id: String): InstanceNetworkInfo
    suspend fun snapshot(id: String, label: String): String
}

class IncusVmSandboxProvider(
    private val socket: Path,           // Incus socket path
    private val project: String,        // Incus project name
    private val imageServer: String,    // Ubuntu cloud images
    private val resourceLimits: ResourceLimits
) : SandboxProvider
```

**Future providers:**
- `DockerSandboxProvider` (containers)
- `DaytonaSandboxProvider` (self-hosted Daytona)
- `FirecrackerProvider` (microVMs)

---

### 3.9 Workspace

The user-facing abstraction that ties everything together:

```
Project
├── Chat (Agent conversation)
├── Terminal (Interactive shell sessions)
├── Files (File browser + editor)
├── Git (Status, diff, log, branch, checkout, commit)
├── Preview (Live preview of running server)
└── Activity (Timeline of agent steps)
```

**Workspace metadata:**
```kotlin
data class WorkspaceMetadata(
    val projectId: String,
    val sandboxId: String,
    val repositoryUrl: String?,
    val branch: String?,
    val workingDirectory: String,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val environment: SandboxEnvironment  // local or remote
)
```

---

## 4. Security Model

### 4.1 Host Protection

| Rule | Enforcement |
|---|---|
| No LLM → host shell path | Agent never gets shell access to host Android |
| No host filesystem | PRoot rootfs isolation |
| No host Docker socket | Not applicable (no Docker) |
| No privileged devices | PRoot without privileged flag |
| No control-plane network | Network policy enforcement |
| No other users' VMs | Per-user sandbox isolation |

### 4.2 VM Security

| Resource | Limit |
|---|---|
| CPU | Per resource profile |
| RAM | Per resource profile |
| Disk | Quota per workspace |
| PID | 512 max |
| Execution timeout | Per command |
| Idle timeout | 30 min default |
| Max lifetime | 24h default |
| Max exposed ports | 5 per sandbox |
| Network policy | Per workspace setting |

### 4.3 Approval Engine Risk Levels

| Level | Examples | Auto-approve in Balanced? |
|---|---|---|
| `READ_ONLY` | fs.read, fs.list, git.log | Yes |
| `WORKSPACE_WRITE` | fs.write, fs.patch, git.checkout | Yes |
| `PACKAGE_INSTALL` | apt install, npm install, pip install | Yes |
| `NETWORK` | fs.read (URL), port.open | Yes |
| `PROCESS_CONTROL` | process.kill, terminal.cancel | No |
| `GIT_WRITE` | git.commit, git.push | No |
| `SECRET_ACCESS` | Read env vars with KEY/TOKEN | No |
| `DESTRUCTIVE` | fs.delete, rm -rf, git reset --hard | No |

**Modes:**
- **Safe**: All tools require approval
- **Balanced**: READ_ONLY, WORKSPACE_WRITE, PACKAGE_INSTALL, NETWORK auto-approved
- **Autonomous**: Everything except DESTRUCTIVE and cross-sandbox

**Never auto-approved (in any mode):**
- Host filesystem access
- Control-plane access
- Secret extraction
- Cross-user sandbox access

---

### 4.4 Secret Handling

- No API keys in `/workspace`
- No keys in git repos
- No keys in shell history
- No keys in logs/transcripts
- No keys in source code
- All secrets stored in `SecretStore` (EncryptedPrefs + Android Keystore)
- Remote: short-lived scoped tokens via credential proxy

**Log redaction (central):**
- `Authorization: Bearer ...` → `Authorization: Bearer ***`
- `api_key=...` → `api_key=***`
- GitHub tokens, SSH private keys
- Any string matching known secret patterns

---

## 5. Error Model

Unified error hierarchy:

```
SandboxError (sealed)
├── AuthError(message)
├── RateLimitError(retryAfter)
├── ProviderUnavailable(message)
├── ModelUnavailable(modelId, message)
├── NetworkError(cause)
├── SandboxUnavailable(sandboxId, message)
├── SandboxTimeout(sandboxId, elapsed, limit)
├── SandboxResourceLimit(resource, current, limit)
├── CommandFailed(exitCode, stdout, stderr)
├── ProcessFailed(pid, signal)
├── PermissionDenied(path)
├── PolicyDenied(policy, reason)
├── IntegrityError(expected, actual)
└── ConfigurationError(field, reason)
```

---

## 6. Feature Flags

```kotlin
object FeatureFlags {
    val ubuntuLocalEnabled: Boolean = true       // Ubuntu 26.04 via PRoot
    val remoteSandboxEnabled: Boolean = true     // Remote VM support
    val incusProviderEnabled: Boolean = true     // Incus as provider
    val remotePreviewEnabled: Boolean = true     // Preview via gateway proxy
    val liteLlmEnabled: Boolean = true           // LiteLLM provider
}
```

---

## 7. Sandbox Modes (User Settings)

| Mode | Behavior |
|---|---|
| **Auto** | Remote VM available → Use Remote; else Local Ubuntu available → Use Local; else show setup requirement |
| **Local Ubuntu** | Force local PRoot Ubuntu 26.04 |
| **Remote Ubuntu VM** | Force remote Incus VM (error if gateway not configured) |

---

## 8. Sandbox Lifecycle States

```
CREATING → BOOTING → READY → BUSY → STOPPED → PAUSED → ERROR → DESTROYING → DESTROYED
```

---

## 9. Definition of Done

When the user says: *"أنشئ مشروع Next.js جديدًا"* the agent must:

1. ✓ Provision Ubuntu sandbox
2. ✓ Set up workspace directory
3. ✓ Install Node.js + npm dependencies
4. ✓ Scaffold Next.js project
5. ✓ Create/modify project files
6. ✓ Run build
7. ✓ Detect and fix errors automatically
8. ✓ Rebuild successfully
9. ✓ Start development server
10. ✓ Expose port 3000
11. ✓ Make preview available in-app

The user can then open: **Chat**, **Terminal**, **Files**, **Git**, **Preview**, **Activity** — and see the real, running project.

---

## 10. Migration Strategy

| Item | Approach |
|---|---|
| Debian 12 installs | Keep, offer "Migrate to Ubuntu" button |
| Alpine 3.19 installs | Keep, offer "Migrate to Ubuntu" button |
| Existing conversations | No migration needed (backend-agnostic) |
| Existing APK builds | Preserve, mark as deprecated |
| OpenCode | Auto-installs on Ubuntu rootfs |
| User projects | No overwrite, no delete source, resumable copy |

---

## 11. Forbidden Practices

- ❌ Docker container as VM security boundary
- ❌ User command via host shell interpolation
- ❌ Direct Android → Incus management API
- ❌ Incus admin credentials to phone
- ❌ Public Sandbox Gateway without Authentication
- ❌ Plaintext secrets
- ❌ Ubuntu rootfs in APK (too large — download from GitHub Releases)
- ❌ Large AI models in APK
- ❌ Describing PRoot as "VM" (call it "Local Ubuntu Environment")
- ❌ Deleting user data during migration
- ❌ Disabling tests to pass CI
- ❌ Mocks to claim Remote Sandbox works
