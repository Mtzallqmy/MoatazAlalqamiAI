# حالة التقدم — v4.1.0 (محدّث 2026-08-19)

## المنجز

### 1. rootfs مضمّن ✅
- `/home/ubuntu/rootfs-build/moataz-debian-rootfs-arm64.tar.xz` = **83MB مضغوط** (421MB uncompressed)
- يحتوي: Debian 12 arm64 + dpkg status entries لـ[bash, ca-certificates, curl, wget, git, nano, less, unzip, python3, tar, coreutils] + OpenCode binary 183MB في usr/local/bin/opencode + profile.d snippet + resolv.conf (8.8.8.8/1.1.1.1)
- تم trimming: usr/share/{doc,locale,man,lintian,linda,vim,i18n}, var/cache/apt, var/lib/apt/lists
- تم النسخ إلى: `androidApp/src/main/assets/moataz-debian-rootfs-arm64.tar.xz` (git added)
- **ملاحظة سبب الفشل القديم**: etc/resolv.conf في LXC tar كان symlink مكسور → /run/systemd/resolve/stub-resolv.conf غير موجود post-extraction → os.path.exists يرجع False → يجب unlink symlink أولاً ثم الكتابة
- prepare_rootfs.py في `/home/ubuntu/rootfs-build/prepare_rootfs.py` — يعمل: `rm -rf extracted && mkdir extracted && tar xJf rootfs.tar.xz -C extracted && python3 prepare_rootfs.py && tar cJf moataz-debian-rootfs-arm64.tar.xz -C extracted .`

### 2. تعديلات المستودع (git status):
- A androidApp/src/main/assets/moataz-debian-rootfs-arm64.tar.xz (83MB)
- M composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/linux/DistroSpec.kt — روابط v3.1.0→v4.1.0 (Debian) و v3.4.0→v4.1.0 (Ubuntu)
- M gradle/libs.versions.toml — appVersion 4.0.0→4.1.0 + versionCode 114→115
- A MentionResolver.kt (جديد) + MentionAutocomplete.kt (جديد) — جارٍ
- M QuestionInput.kt — autocomplete @mentions

### 3. MentionResolver.kt (منشأ في composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/chat/MentionResolver.kt)
- MentionResolver(sandbox: SandboxController) — regex `@(/[...]|[...]+)`
- resolve(message): يُلحق محتوى كل ملف بعد النص كـ`--- mentioned: path --- ... --- end mentions ---`
- rawMentions/detectMentionQuery/applyMentionSuggestion (internal, اختبارية)
- collectMentionCandidates(sandbox, rootPaths=[/root/projects,/root,/home], depthLimit=2, maxCandidates=40)
- MentionAutocomplete.kt — composable على غرار SkillAutocomplete، أيقونات Folder/InsertDriveFile

## معلومات بنية أساسية (للاستكمال)

### أوامر البناء:
```bash
cd /home/ubuntu/MoatazAlalqamiAI
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :composeApp:compileAndroidMain 2>&1 | grep "^e: " | head -20
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :composeApp:testAndroid 2>&1 | grep -E "tests completed|failed|BUILD" | tail -3
env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ./gradlew :androidApp:assembleFossRelease -Pandroid.injected.signing.store.file=/home/ubuntu/release-apks/release.keystore -Pandroid.injected.signing.store.password=moataz2024 -Pandroid.injected.signing.key.alias=moataz-ai -Pandroid.injected.signing.key.password=moataz2024 2>&1 | tail -5
```

### GitHub:
- Repo: Mtzallqmy/MoatazAlalqamiAI، branch: feature/ai-gateway-hardening
- gh auth يعمل (ghu token في env GH_TOKEN) — التوكن github_pat لا يعمل مباشرة مع gh
- Release v4.0.0 موجود (فقط APK، بدون rootfs asset — هذا سبب فشل التنزيل سابقاً)
- Release v3.1.0 فيه moataz-debian-rootfs-arm64.tar.xz
- Keystore: /home/ubuntu/release-apks/release.keystore (moataz2024, moataz-ai)

### بنية MentionResolver المطلوبة تكامل مع:
- ChatViewModel (composeApp/src/commonMain/kotlin/com/inspiredandroid/kai/ui/chat/ChatViewModel.kt):
  - `askInternal` سطر 196 — يستدعي `dataRepository.ask(strippedQuestion, files, uiSubmission, activeSkillId)` سطر 227
  - ChatViewModel يأخذ حالياً (dataRepository, taskScheduler, backgroundDispatcher, localNetworkPermissionController) — يجب إضافة MentionResolver عبر koin (AppModule.kt)
  - parseSkillInvocation سطر 305 — نموذج مشابه
- ChatScreen.kt:
  - ChatScreen(viewModel, ...) سطر 132 — يضيف koinInject<SandboxController>()
  - ChatScreenContent سطر 153 (يعرض InteractiveModeScreen سطر 169 + ChatModeScreen سطر 175 + KaiBuildScreen)
  - QuestionInput عند سطر 274 (interactive mode input) وسطر 927 (chat mode)
  - ChatModeScreen سطر ~355, InteractiveModeScreen سطر ~191 — كلاهما يستدعي QuestionInput
- AppModule.kt (commonMain): viewModel ChatViewModel سطر 203 — يجب إضافة MentionResolver injection
- QuestionInput.kt: أُضيف param sandboxController: SandboxController? = null + منطق autocomplete يعمل

### إنجاز @mentions ✅ كامل (compile ناجح، كل الملفات المعدلة):
1. MentionResolver.kt (commonMain/ui/chat/) — resolve() يُلحق المحتوى، rawMentions يعيد مسارات ببادئة @، detectMentionQuery/applyMentionSuggestion internal
2. MentionAutocomplete.kt (composables/) — عرض قائمة @ عند الكتابة
3. QuestionInput.kt — param sandboxController + mention autocomplete (LaunchedEffect + collectMentionCandidates)
4. ChatViewModel.kt — ctor يحتوي mentionResolver، resolve() داخل viewModelScope.launch قبل ask
5. AppModule.kt — single MentionResolver + تمريره إلى ChatViewModel
6. ChatScreen.kt — ChatScreen/ChatScreenContent/InteractiveModeScreen/ChatModeScreen جميعها sandboxController param
7. App.kt — يمرر sandboxController إلى ChatScreen
8. ChatViewModelTest x4 ملفات + MentionResolverTest.kt جديد (12 اختبارًا)

### حالة الاختبارات الحالية (جارٍ):
- 1059 اختبارًا، 3 فاشلين — كلها في MentionResolverTest.kt فقط:
  - rawMentions normalizes double slashes — فشل بعد إصلاح rawMentions لإضافة @
  - resolve inlines file content — فشل
  - unreadable mentions surface as markers — فشل
- السبب المرجح: في tests، SandboxController anonymous object by NoOpSandboxController() — NoOpSandboxController methods غير مفتوحة؟ لا — interface methods. الأرجح أن resolve يندخل sandbox.readTextFile ويرمي → لكن TextFileResult.Unreadable. أو listDirectory في anonymous object غير يتجاوز interface الافتراضي. يجب فحص: interface SandboxController له listDirectory implementation افتراضي في NoOpSandboxController؟ — في NoOpSandboxController override suspend fun listDirectory = emptyList() لكن في anonymous `object : SandboxController by NoOpSandboxController()` يجب أن يعمل. الاحتمال: SandboxFileEntry path field أو readTextFile يرمي UnsupportedOperationException في base.

### المتبقي:
1. إصلاح 3 اختبارات MentionResolver الفاشلة ثم testAndroid كامل (1047 سابق + جديد)
2. APK v4.1.0 assembleFossRelease + توقيع
3. commit + push feature/ai-gateway-hardening → PR → merge
4. Release v4.1.0: gh release create + upload (rootfs asset 83MB من /home/ubuntu/rootfs-build/ + APK)
5. تسليم APK للمستخدم
