# Architecture — Moataz Alalqami AI

## 1. Overview

التطبيق تطبيق أندرويد أصلي مبني على Kotlin Multiplatform، حيث المنطق المشترك (100%) يعيش في `composeApp/src/commonMain` وطبقة الربط الأندرويدية في `androidApp`.

```
┌──────────────────────────────────────────────────────┐
│  UI (Compose Multiplatform)                          │
│  ChatScreen · SettingsScreen · ProjectsScreen        │
│  AgentsScreen · ActivityScreen                       │
├──────────────────────────────────────────────────────┤
│  Domain                                              │
│  gateway/     agents/     projects/   integrations/  │
├──────────────────────────────────────────────────────┤
│  Data: AppSettings · Service · RemoteDataRepository  │
│  security/  ·  Koin DI  ·  SQLDelight                │
├──────────────────────────────────────────────────────┤
│  Platform: Android (Keystore, EncryptedPrefs, OkHttp)│
└──────────────────────────────────────────────────────┘
```

## 2. AI Gateway (`gateway/`)

### 2.1 UrlNormalization + ProtocolAdapters
كل مزود لدية بروتوكول: `OpenAIChatCompletions`، `OpenAIResponses`، `AnthropicMessages`، `GeminiNative`، `Ollama`، `LiteRt`. المحوّل يعرف مسارات chat/models خاصته. الضمانات:
- لا تكرار `/v1`.
- رفض أي URL يحوي `user:pass@` أو `?key=...` قبل الإرسال.
- تبديل http→https للمزودات الإنتاجية.

### 2.2 ModelMetadata + Pricing
`ModelCapabilityCatalog`: طبقة قدرات يدوية فوق `ModelCatalog` المكتشف — supportsVision، supportsToolCalling، supportsReasoning، isLocal، pricing لكل مليون توكن، speed/quality tiers.

### 2.3 ProviderHealthRegistry
سجل صحة لكل instance: `Unknown → Connected / AuthError / NetworkError / RateLimited / ModelUnavailable / Disabled`. الفحوص لا تعمل تلقائيًا (حماية للرصيد) — فقط عند "Test Connection" أو خطأ ملاحظة. `isUnhealthy()` تستبعد المزود المؤقت.

### 2.4 ModelRouter + TaskClassifier
1. **TaskClassifier**: كلمات مفتاحية heuristic فقط — لا استدعاء LLM.
2. **RoutingProfileConfig**: 11 ملفًا — لكل مهمة نموذج صريح اختياري (`codingModelId`...)، allow/block lists، `cloudAllowed`، `localPreferred`، `maxCostPerRunUsd`، `fallbackChain`.
3. **selectModel**: فلترة صارمة (hard rejection تُستبعد نهائيًا، unhealthy مؤقتة فقط) ثم تسجيل مرجح حسب الملف.

### 2.5 UsageRecorder
سجل طلبات (tokens، نجاح، تكلفة تقديرية) مع نافذة اليوم/الشهر وفحص سقف ميزانية شهري وسقف 5000 سجل.

## 3. Agent Runtime (`agents/`)

- **AgentRun** / **AgentStep**: كل خطوة (أداة/نص/موافقة) تُسجل مع cost وduration.
- **ApprovalEngine**: `decide(toolId, risk, mode, args)`:
  - أداة مجهولة → `NeedsApproval` دائمًا (Anti-Injection).
  - `isDestructiveGit()` يحظر `reset --hard`، `clean -fd`، force push.
  - الأنماط: Safe (كل شيء)، Balanced (reads/writes محلي تلقائي)، Autonomous (كل شيء ما عدا Dangerous).
- **AgentRunStore**: يحفظ runs وpending approvals في settings.

## 4. Security (`security/`)

- **SecretStore**: `get/put/remove` بـ key prefix (`provider:`, `integration:`) فوق EncryptedPrefs (Keystore).
- **SecretStoreMigrationRunner**: عند أول تشغيل ينقل كل `apiKey` قديم من AppSettings إلى SecretStore ويحذف الأصل.
- **ProviderCredentialsResolver**: وسيط موحّد يقرأ من SecretStore مع fallback للذاكرة أثناء الجلسة.
- مفاتيح GitHub/Telegram في نفس SecretStore تحت `integration:`.

## 5. Data Flow (طلب محادثة)

1. المستخدم يرسل رسالة.
2. Auto Router: `TaskClassifier.classify()` → `ModelRouter.selectModel()` → instance.
3. `ProviderHealthRegistry.isUnhealthy()` → fallback إن لزم.
4. `RemoteDataRepository` يجلب apiKey من `ProviderCredentialsResolver` → Ktor/OkHttp → stream.
5. `UsageRecorder.record()` + تحديث health عند أخطاء.

## 6. Testing

`composeApp/src/commonTest` — 450+ اختبارًا يغطي Gateway وRouter وApproval وUsage وMarkdown وغيرها، تُشغَّل عبر `:composeApp:testAndroid` (Android Host Test).

## 7. Local development runtime

The production local path is a single Debian 13 Trixie arm64 installation.
`LinuxInstaller` owns verified/atomic installation, `EnvironmentDoctor` owns
readiness, and `EnvironmentRepairExecutor` owns targeted repair. All local
development consumers bind the same projects directory at `/workspace` (plus
the legacy `/root/projects` alias). See `RUNTIME_ARCHITECTURE.md`.

The existing VT/PTY implementation remains the execution core. Stable contracts
under `terminal/` and the generic `CliRegistry` prevent developer-tool providers
from entering terminal parsing/rendering. See `TERMINAL_ARCHITECTURE.md` and
`CLI_EXTENSION_GUIDE.md`.
