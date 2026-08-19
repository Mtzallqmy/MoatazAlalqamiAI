# تصميم ميزة @mentions في الدردشة (v4.1.0)

## السلوك المطلوب
عند كتابة المستخدم `@` في حقل الدردشة تظهر قائمة اقتراحات من:
- المشاريع والملفات الموجودة في sandbox (rootfs ~ /root، خصوصاً ~/projects)
- عند اختيار ملف/مسار يُدرج `@<path>` في النص
- عند الإرسال: تُحلَّ كل `@<path>` بإدراج محتوى الملف من sandbox داخل الـprompt (مع حدود حجم)، والنص يظل كما كتبه المستخدم

## نقاط التكامل (من الفحص)
- `ChatViewModel.parseSkillInvocation` — نموذج موجود لمعالجة باديئة `/skill` قبل askInternal. سنضيف parseMentions بشكل مشابه لكن يعمل على كامل النص (regex `@([^\s@]+)`)
- `askInternal` في ChatViewModel يستدعي `dataRepository.ask(strippedQuestion, files, ...)`
- `SandboxController : FileBrowserSource` — يملك `readTextFile(path)` و `listDirectory(path)` و `executeCommand`
- `DataRepository.ask` يضيف User history مع attachments
- السؤال: كيف نحصل على SandboxController داخل ChatViewModel؟ عبر Koin `koinInject()` (نفس ما يفعله SandboxTabsContent: koinInject<SandboxController>())

## القرار المعماري
1. فئة جديدة: `com.inspiredandroid.kai.ui.chat.MentionResolver` (commonMain)
   - constructor(sandboxController: SandboxController) — NoOpSandboxController على غير Android يرجع Unreadable
   - `data class Mention(val raw: String, val resolvedPath: String, val content: String?)`
   - `fun resolve(text: String): String` — يعيد النص + يلحق content كل ملف في نهاية الـprompt كمقاطع `--- <path>: ---` (لا نعدل نص المستخدم المرئي)
   - أو نلحق المحتوى داخل الـmessage؟ الأفضل: إلحاق في نهاية نص الـprompt المرسل بعد النص الأصلي (الموديل يرى كل شيء) — أبسط، ولا نكسر عرض الـhistory
   - نستخدم `executeCommand("cat <file>")` لأن SandboxController يملك readTextFile عبر FileBrowserSource أيضًا — نستخدم readTextFile
2. قائمة اقتراحات عند كتابة `@`: Sheet من الملفات في ~/projects و /root — نحصل عليها عبر listDirectory. اقتراحات: @projects/ (مجلدات)، وملفات نصية.
   - عرض sheet مخصص: `MentionSuggestionsSheet` يستدعي `listDirectory("/root")` و `listDirectory("/root/projects")`
3. في ChatScreen: عند اكتشاف `@` في نهاية النص (أو كـtoken) نظهر السيلت + اختيار يُدرج `@path` في النص
4. قبل send: نحل الـmentions ونلحق المحتوى

## حدود
- حجم الملف: 64KB لكل ملف (نفس حد readFile)
- max mentions per message: 10
- مسارات غير مقروءة: تُستبدل بعلامة [unreadable: path] ولا تفشل الإرسال

## حالة sandbox
- SandboxController.status يحمل status — إذا غير active نعامل Unreadable بصمت
- Default session: SandoxSessions.DEFAULT
