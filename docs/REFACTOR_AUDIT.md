# PHASE 1 — Repository Audit Report (v3.8.0 refactor)

التاريخ: 19 أغسطس 2026 | الحالة: v3.7.0 منشور، 973 اختبار 0 فشل، فرع feature/ai-gateway-hardening

## 1. خريطة المعمارية الحالية

```
composeApp (Kotlin Multiplatform + Compose)
├── com.inspiredandroid.kai
│   ├── data/          RemoteDataRepository (2344 سطر، 199 دالة — God Object) + ToolExecutor + AppSettings
│   ├── ui/            chat(584)/build(KaiBuildScreen)/settings/workspace/components
│   ├── ui/dynamicui/  KaiUiParser (kai-ui protocol داخلي)
│   ├── agents/        AgentOrchestrator(413) + AgentRuntime + ApprovalEngine + ApprovalAuditLog
│   ├── tools/         ToolRuntime (23 أداة + 7 browser) + BrowserTools + permissions
│   ├── gateway/       AiGatewayCoordinator + ModelRouter + TaskClassifier + FallbackStrategy + ProviderHealth + UsageRecorder
│   ├── sandbox/       backend (SandboxBackend + LocalProot + Remote) + remote (SandboxProvider)
│   ├── browser/       BrowserEngine + BrowserPolicy + LightpandaGatewayBackend
│   ├── linux/         LinuxInstaller + TarExtractor + DistroSpec
│   ├── hotupdate/     RemoteConfigService + DynamicToolExecutor + FeatureFlags (v3.7.0)
│   ├── security/      SecretStore + ProviderCredentialsResolver
│   ├── security/manifest  (جديد في هذا الـrefactor)
│   └── App.kt + AppModule (Koin)
└── androidApp (Android entry)
```

## 2. جرد Branding "Kai" (التصنيف)

### A. User-visible — يجب تغييره:
| الموقع | القيم | الإجراء |
|---|---|---|
| strings.xml (57 occurrence) | kai_build_* (Open Kai Build, Kai Build setup, ...), "Kai heartbeat", "Kai Build loses its Linux", "shared with Kai Build", "Notification access ... enable Kai" | إعادة صياغة/إزالة |
| strings.xml:137-138 | "Pick which apps Kai can read" | "Moataz Alalqami AI" |
| kai_ui_render_failed, kai_ui_code_copy | internal error ids — تُحتفظ كـresource keys، النص الظاهر يُغيّر |
| TaskScheduler.kt:184 | notification title "Kai heartbeat" | "Moataz AI Heartbeat" |
| KaiBuildController + KaiBuildScreen | اسم الميزة يُعرض للمستخدم | إعادة تسمية واجهة: BuildController/BuildScreen (مع alias للـKoin keys) |

### B. Internal code identifiers — migration تدريجي (هذا الـrefactor):
- `com.inspiredandroid.kai` package: يبقى كما هو (تغيير الـpackage الكامل في جلسة واحدة = مخاطر تفجير البناء) — سنضيف فقط modules جديدة بدون prefix Kai، ونبدأ إزالة kai-ui من protocol strings فقط حيث لا يكسر التوافق.
- `applicationId = "com.inspiredandroid.kai"`: **لا يُغيَّر** — التطبيق منشور للمستخدمين بنفس الـid ويجب أن تستمر التحديثات فوقه (قاعدة إلزامية في البرومبت).
- Resource keys مثل `kai_ui_*`, `kai_build_*`: تبقى keys (compatibility) مع تغيير النصوص الظاهرة.

### C. Persistent compatibility identifiers — لا تُغيَّر:
- Settings keys: `kai_build_launch_agent`، SharedPreferences prefixes، SQLDelight database name (kai.db إن وُجد)
- File paths على الجهاز، WorkManager/Notification channel ids
- Koin module names: `kaiBuildFiles`

### D. Legal attribution — تُحفظ:
- LICENSE (Apache-2.0), NOTICE, THIRD_PARTY_LICENSES — تبقى كما هي دون تغيير
- composable generated resources package `kai.composeapp.generated.resources.*` — مولَّد تلقائيًا من اسم المشروع، لا يُلمس

### E. Third-party references — لا تُغيَّر:
- اسم "Kai" كإشارة لـKai 9000 الأصلي (SimonSchubert/Kai) في LICENSE
- أي مراجع لأدوات طرف ثالث

## 3. God Objects (الأولوية)

1. **RemoteDataRepository (2344 سطر، 199 دالة، 21 dependency)** — تدمج: chat/LLM loop + SMS + Email + Notifications + Heartbeat + Tasks + Memory + Skills + MCP + Providers + Local AI. التفكيك يبدأ بفصل نطاق **Chat/Provider** (الأكثر استخدامًا والأقل مخاطرة) إلى `ChatSessionRepository` مع إبقاء RemoteDataRepository كـdelegator كامل (كل استدعاءاته القديمة تعمل — لا كسر).
2. ChatViewModel (584) — مقبول حاليًا؛ تفكيكه لاحقًا.
3. ToolExecutor/DataRepository أصغر وأدوار واضحة.

## 4. Security-sensitive components (للمعالجة)
- ApprovalEngine (agents/) — حالياً string-matching؛ الترقية إلى PolicyEngine بهيكلة ToolRisk/PolicyDecision مع تحليل argv هيكلي.
- TarExtractor (androidMain) — إضافة path traversal/tar bomb validation.
- RemoteConfigService — ترقية إلى Signed Manifest (توقيع Ed25519 مع public key مضمّن، last-known-good).
- SecretStore — سليم (Keystore-backed)؛ إضافة redaction layer موحدة في اللوج.

## 5. Native components
- PRoot/Linux: لا إعادة كتابة (قاعدة البرومبت) — hardened validation layer فقط.
- LiteRT/C++ inference: يبقى كما هو.
- Rust moataz-core: **يؤجَّل** — يتطلب Rust toolchain/Native build داخل Gradle (cargo-ndk) وهو تغيير بنية ضخم يكسر CI ويحتاج مراجعة المستخدم؛ سيُنفَّذ الـPolicy Engine + manifest verification كـKotlin نقي مع نفس العقود (C ABI جاهز للتبديل مستقبلًا). هذا يحافظ على: بناء ناجح، اختبارات خضراء، ولا تعقيد متعدد اللغات دون فائدة واضحة في هذه الجلسة.

## 6. Remotely updateable candidates (مدعوم الآن عبر remote-config/config.json)
- feature flags, prompts/personas, dynamic tools ✅ موجود
- الجديد: provider definitions, model metadata/catalog, routing weights, rootfs URLs/SHA256 — يُضاف عبر remote-config schema (v2) مع signed manifest layer.

## 7. الترتيب التنفيذي المعتمد (محافظ/آمن)
1. Branding مرئي (strings, notifications, Build screen label) — تغيير نصوص فقط، لا سلوك.
2. تفكيك ChatSessionRepository من RemoteDataRepository (delegation).
3. PolicyEngine موحد (ToolRisk/Capability/PolicyDecision ALLOW/ASK/DENY) + argv analysis (git force, rm recursive, curl|sh, credentials).
4. Tar/archive path-security hardening + atomic install marker discipline.
5. AI Gateway V2: RoutingProfile موحد (weights centralized, testable) + scoring factors.
6. Signed Remote Manifest: Ed25519 verify فوق remote-config (graceful: unsigned → accepted in debug/fallback to cached).
7. اختبارات أمنية: path traversal, tar bombs, destructive argv, manifest tampering, signature failure.
8. v3.8.0 APK + release.
