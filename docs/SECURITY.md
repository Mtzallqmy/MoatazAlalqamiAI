# Security Model — Moataz Alalqami AI

## 1. Threat Model

| التهديد | التخفيف |
|---|---|
| سرقة مفاتيح API أو كلمات مرور البريد أو headers الخاصة بـMCP من SharedPreferences | بيانات الاعتماد تحفظ في EncryptedPrefs فوق Android Keystore، وتبقى في الإعدادات مراجع غير سرية فقط |
| Prompt Injection عبر أدوات مجهولة | ApprovalEngine يرفض تنفيذ أي أداة غير موجودة في allow-list الأدوات المعروفة، ويحولها لطلب موافقة بشري |
| أدوات مدمرة (git reset --hard، force push) | محظورة نهائيًا في ApprovalEngine مهما كان وضع التشغيل؛ لا يمكن لأي مخرَج أداة أن يعدّل سياسة الموافقات |
| تسريب مفاتيح عبر عناوين URL مخصصة | `UrlNormalization` ترفض عناوين تحوي `user:pass@` أو `?key=...` قبل أي طلب |
| Telegram Bot غير مقيد | `allowedChatIds` إجباري؛ الرسائل من محادثات خارج القائمة تُتجاهل صامتًا |
| استهلاك ميزانية غير مقصود | سقوف شهرية لكل تشغيل مع تقدير فوري قبل الإرسال وفلترة المرشحين فوق السقف |
| حرق رصيد المستخدم بالفحوص التلقائية | لا فحوص صحة تلقائية؛ الفحص عند الطلب فقط |
| تسريب الأسرار في ملف تصدير الإعدادات | ملفات JSON المستخرجة تحذف مفاتيح API وكلمات المرور وheaders الحساسة وتضع `secrets_omitted=true` |
| تسريب الأسرار في تشخيص Runtime | redaction قبل الاحتفاظ بالسجل، بما يشمل JSON وCLI arguments وBearer وcredential URLs ومفاتيح SSH الخاصة |
| RootFS أو أرشيف متلاعب به | SHA-256 لكل جزء وللصورة، manifest يطابق Debian 13 arm64، رفض traversal/الروابط الخطرة والأرشيف المبتور |

## 2. Cryptography
المفتاح الأساسي لـ EncryptedPrefs مشتق من Android Keystore (`AES-GCM`)، ولا يُخزَّن على القرص. كل secret له prefix نطاقي (`provider:`، `integration:`) ويُحذف مع إزالة المزود.

## 3. Migration Safety
`SecretStoreMigrationRunner` ينقل مفاتيح API القديمة، بينما تنقل كلمات مرور
البريد وMCP credential headers إلى SecretStore عند الوصول أو تهيئة مدير
الخوادم. يبقى استيراد ملفات JSON القديمة مدعومًا للتوافق، لكن التصدير الجديد
لا يعيد إنشاء نسخة plaintext للأسرار.

## 4. Principles
1. Secrets لا تُخزن كنص عادي أبدًا.
2. السياسة (approval policy) read-only أثناء تشغيل الوكيل.
3. الفشل الآمن: عند أي استثناء في security layer يُفشل الطلب بأمان ولا يسقط إلى مسار غير مشفر.
4. شهادة Release المستخدمة في CI مؤقتة للتحقق من سلامة wiring التوقيع فقط؛ لا تمثل مفتاح النشر الإنتاجي.
