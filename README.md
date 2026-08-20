# Moataz Alalqami AI

**Android AI Agent** — تطبيق أندرويد ذكي للوكلاء الاصطناعيين، مبني على Kotlin Multiplatform.

تطبيق محادثة وتفاعل مع أكثر من 30 مزود ذكاء اصطناعي (OpenAI، Anthropic، Google Gemini، OpenRouter، Groq، Ollama، والنماذج المحلية عبر LiteRT)، مع **Moataz Gateway** لتوجيه المهام تلقائيًا، ونظام **Moataz Agents** يشغّل الوكلاء تحت إشراف المستخدم، وتكاملات GitHub وTelegram.

> مبني على أساس مشروع Kai 9000 مفتوح المصدر (Apache-2.0) مع تعديلات واسعة: هوية جديدة، تشفير آمن لمفاتيح API، بوابة ذكاء اصطناعي، نظام وكلاء، وموثّقات أمان وخصوصية كاملة.

---

## الميزات الرئيسية

### AI Gateway — التوجيه الذكي للنماذج
- **Model Router**: 11 ملف تعريف توجيه جاهزًا (Auto/Balanced، MaximumQuality، Fast، Economy، Coding، Reasoning، Research، Vision، LocalFirst، PrivacyLocalOnly، Custom قابل للتعديل).
- **TaskClassifier**: تصنيف فوري للمهمة (Chat، Coding، Reasoning، Research، Vision، Summarization، FastAnswer، Planning) من رسالة المستخدم بدون أي استدعاء LLM.
- **Auto Mode**: اختيار النموذج الأمثل تلقائيًا لكل رسالة حسب نوع المهمة والإعدادات.
- **Fallback**: سلسلة احتياطية بين المزودين عند الفشل أو عدم الصحة.
- **Provider Health**: مراقبة صحة كل مزود (AuthError، RateLimit، NetworkError) مع فترة تبريد تحمي رصيد المستخدم.
- **URL Normalization protocol-aware**: تطبيع عناوين المزودين وفق بروتوكول كل مزود (OpenAI Chat/Responses، Anthropic Messages، Gemini Native، Ollama، LiteRT) مع رفض أي عنوان يحوي بيانات اعتماد.

### أمان وتخزين مشفر
- **SecretStore**: كل مفاتيح API وTokens تحفظ مشفرة في Android Keystore-backed EncryptedPrefs — لا نص عادي إطلاقًا.
- **مهاجر تلقائي**: ينقل مفاتيح API القديمة من التخزين النصي إلى التخزين المشفر عند أول تشغيل.
- **Approval Engine** للوكلاء: Safe / Balanced / Autonomous — مع حظر نهائي للعمليات المدمرة (`git reset --hard`، `git clean -fd`، force push) وحظر تشغيل أي أداة مجهولة (حماية من Prompt Injection).

### Agent Runtime
- **Runs & Steps**: تشغيل متتالي للأدوات مع سجل كامل محفوظ.
- **Activity Timeline**: سجل زمني لكل ما قام به الوكيل (أدوات، موافقات، طلبات مزود، تكاليف).
- **Cost Control**: سقف تكلفة لكل تشغيل ولكل شهر مع تقدير فوري.

### التكاملات
- **GitHub**: استنساخ، commit، push/PR عبر Personal Access Token محفوظ في SecretStore.
- **Telegram**: Bot يحترم allow-list إجباري للمحادثات (لا يستجيب لأحد خارج القائمة).
- **Projects**: مساحة مشاريع مع scopes للذاكرة.

### النماذج المحلية
LiteRT لتشغيل نماذج GGUF/ONNX على الجهاز بدون إنترنت — مثالي مع Profile **PrivacyLocalOnly**.

### Moataz Runtime وMoataz Code

- Debian 13 Trixie arm64 تحت PRoot، بصورة rootfs مضمّنة وmanifest/SHA-256.
- `/workspace` هو جذر المشاريع الموحد، مع mount توافق إلى `/root/projects`.
- Ready لا تُعرض إلا بعد فحص PRoot وshell وCLI والملفات وPTY.
- Moataz Terminal تحتفظ بمحرك PTY/VT الحقيقي، وCLI Registry يفصل Claude Code وOpenCode وGrok عن terminal core.

---

## البنية التقنية

```
composeApp/
  src/commonMain/kotlin/com/inspiredandroid/kai/
    gateway/      — AI Gateway: UrlNormalization, ModelRouter, TaskClassifier,
                    ProviderHealth, UsageRecorder, ModelMetadata
    agents/       — Agent Runtime: AgentRun, AgentStep, ApprovalEngine, AgentRunStore
    projects/     — Workspace: Project, ProjectStore
    integrations/ — GitHubService, TelegramService
    security/     — SecretStore, SecretStoreMigrator, ProviderCredentialsResolver
    data/         — Settings, Services, Models, RemoteRepository
    ui/           — Screens (Kotlin Compose Multiplatform)
androidApp/       — Android glue: Application, Activities, Resources
```

| المكون | الوصف |
|---|---|
| Kotlin Multiplatform | منطق مشترك 100%، أندرويد هو الهدف الوحيد |
| Kotlin Compose | واجهة Native عصرية |
| Koin | حقن تبعيات |
| SQLDelight | تخزين محلي (محادثات، مهام مجدولة) |
| OkHttp / Ktor | شبكة |
| EncryptedPrefs | تشفير عبر Android Keystore |
| LiteRT | تشغيل نماذج محلي |
| OkHttp WebSocket | Streaming فوري |

## البناء

```bash
# متطلبات: JDK 21، Android SDK 37، NDK
./gradlew :androidApp:assembleFossDebug     # APK بدون خدمات Google
./gradlew :androidApp:assemblePlayStoreDebug
./gradlew :composeApp:testAndroid           # 450+ اختبار وحدة
```

APK الناتج: `androidApp/build/outputs/apk/foss/debug/androidApp-foss-debug.apk`

## CI

يُبنى الاختبار مع `testAndroid` في GitHub Actions (`.github/workflows/build.yml`): JDK 21 + Android SDK 37 + gradle test + APK assemble.

## الوثائق

| الملف | المحتوى |
|---|---|
| `README.md` | هذا الملف |
| `docs/ARCHITECTURE.md` | بنية النظام: Gateway، Router، Agent، Security |
| `docs/BRAND_IDENTITY.md` | هوية المنتج وحدود التوافق |
| `docs/RUNTIME_ARCHITECTURE.md` | عقد Debian 13 والفحوص والإصلاح |
| `docs/TERMINAL_ARCHITECTURE.md` | PTY/VT وحدود terminal core |
| `docs/CLI_EXTENSION_GUIDE.md` | إضافة CLI جديدة بأقل تغييرات |
| `docs/SECURITY.md` | نموذج التهديدات وقرارات الأمان |
| `docs/PRIVACY.md` | سياسة الخصوصية وبيانات المستخدم |
| `LEGAL_COMPLIANCE.md` | الترخيص وأصل المشروع |

## الترخيص

Apache License 2.0 — مبني على [Kai 9000](https://github.com/SimonSchubert/Kai). انظر `LEGAL_COMPLIANCE.md` للتفاصيل الكاملة.
