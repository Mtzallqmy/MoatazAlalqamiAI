# Security Model — Moataz Alalqami AI

## 1. Threat Model

| التهديد | التخفيف |
|---|---|
| سرقة مفاتيح API من SharedPreferences | كل المفاتيح مشفرة في EncryptedPrefs فوق Android Keystore — لا يمكن قراءتها حتى بجذر الجهاز العادي |
| Prompt Injection عبر أدوات مجهولة | ApprovalEngine يرفض تنفيذ أي أداة غير موجودة في allow-list الأدوات المعروفة، ويحولها لطلب موافقة بشري |
| أدوات مدمرة (git reset --hard، force push) | محظورة نهائيًا في ApprovalEngine مهما كان وضع التشغيل؛ لا يمكن لأي مخرَج أداة أن يعدّل سياسة الموافقات |
| تسريب مفاتيح عبر عناوين URL مخصصة | `UrlNormalization` ترفض عناوين تحوي `user:pass@` أو `?key=...` قبل أي طلب |
| Telegram Bot غير مقيد | `allowedChatIds` إجباري؛ الرسائل من محادثات خارج القائمة تُتجاهل صامتًا |
| استهلاك ميزانية غير مقصود | سقوف شهرية لكل تشغيل مع تقدير فوري قبل الإرسال وفلترة المرشحين فوق السقف |
| حرق رصيد المستخدم بالفحوص التلقائية | لا فحوص صحة تلقائية؛ الفحص عند الطلب فقط |

## 2. Cryptography
المفتاح الأساسي لـ EncryptedPrefs مشتق من Android Keystore (`AES-GCM`)، ولا يُخزَّن على القرص. كل secret له prefix نطاقي (`provider:`، `integration:`) ويُحذف مع إزالة المزود.

## 3. Migration Safety
`SecretStoreMigrationRunner` يعمل مرة واحدة فقط (markers في Settings)، يقرأ النص القديم، يكتب المشفر، ثم يحذف الأصل في نفس المعاملة، ويقبل الفشل الجزئي دون كسر التطبيق.

## 4. Principles
1. Secrets لا تُخزن كنص عادي أبدًا.
2. السياسة (approval policy) read-only أثناء تشغيل الوكيل.
3. الفشل الآمن: عند أي استثناء في security layer يُفشل الطلب بأمان ولا يسقط إلى مسار غير مشفر.
