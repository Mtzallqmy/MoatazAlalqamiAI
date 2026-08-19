# PHASE 14 — تصميم رفع وتحليل الملفات بكل الصيغ

## الوضع الحالي (ما يعمل الآن)
- المرفقات تُرفع في الدردشة (PlatformFile) وتُشفَّر base64 في `Attachment(data, mimeType, fileName)`.
- النصيات (text/*, json, xml, yaml, js) تُفك ترميزها وتُدمج في نص الرسالة كـprefix.
- الصور → `image_url` (OpenAI-compatible) / `image` (Anthropic) / `inline_data` (Gemini).
- PDF → document block (Anthropic فقط)؛ OpenAI-compatible وGemini يتجاهلان PDF.

## الفجوة (ما يشتكي منه المستخدم)
1. PDF/DOCX/PPTX/XLSX/CSV/ZIP وغيرها **لا تُحلل** على الأجهزة — تُخزن فقط.
2. النماذج غير الداعمة للرؤية/المستندات (معظم نماذج OpenAI-compatible) **تفقد** المرفق.
3. لا يوجد أداة "agent" تقرأ الملفات وتحللها في Sandbox.

## الحل (PHASE 14)
### 1. FileAnalysis.kt (commonMain — shared logic)
مُحلل وثائق يعمل بدون LLM في الطبقة المشتركة، يحوّل أي ملف إلى نص قابل للفهم:
- PDF → نص عبر `org.apache.pdfbox` غير متوفر في common → الحل: extraction نصي بسيط (extract text streams) أو الاعتماد على `pdf2text` في Sandbox — **لا**: الأفضل أداة agent حقيقية.
- قرار: التحليل يتم على-device لملفات النص/CSV/JSON، وعلى Sandbox (Linux tools: `pdftotext`, `catdoc`, `python3`) للوثائق الثنائية.
- `FileAnalyzer.kt`: دالة `analyzeContent(Attachment): String` تستخرج نصًا وصفياً من: txt, md, csv, json, xml, yaml, js, py, kt, html, css, sql, log.
- للوثائق الثنائية (PDF/DOCX/XLSX/PPTX/ZIP/Images): نمرر attachment للأداة `analyze_file` في Sandbox.

### 2. أداة الوكيل: analyze_file (FileAnalysisTool.kt في tools/)
- input: fileName + base64 data + mimeType
- تنسخ الملف إلى sandbox (`write_file` → /root/uploads/), ثم:
  - PDF → `pdftotext -layout` + وصف عدد الصفحات
  - DOCX → `python3 -c` (zip XML text extraction) أو `catdoc`
  - XLSX → CSV أول ورقة بـpython3 zipfile+xml
  - PPTX → استخراج نصوص الشرائح
  - ZIP/TAR → قائمة محتويات (مع حماية tar bomb limits من PolicyEngine!)
  - صورة → وصف نصي (EXIF + أبعاد + size) + خيار الرؤية لو النموذج يدعم
- output: نص ملخص قابل للإرسال للنموذج في tool_result

### 3. ChatUiState: تحليل قبل الإرسال
- `Attachment.fileSummary()` — توليد وصف نصي قصير لكل مرفق (اسم + حجم + نوع) يُدرج في نص الرسالة دائمًا حتى لو لم يدعم النموذج الرؤية، بحيث يفهم الوكيل ماهية الملف.

### 4. رفع بصيغة أي ملف
- ملف extensions مسموحة: `supportedFileExtensions` موجود في ChatUiState (يأتي من الإعدادات) — نتأكد أن FileKit يقبل كل الصيغ `FileExtension.Any` في Android picker.

### 5. أمان
- حماية tar bomb/path traversal في unpack الملفات المرفوعة داخل الأداة (PolicyEngineTarLimits)
- حد حجم للمرفق (8MB base64 ≈ 6MB binary) — رسائل أطول من ذلك تُفشل بوضوح

## اختبار
- FileAnalyzerTest: 6 ملفات نصية (csv/json/xml/md/txt/sql)
- FileAnalysisToolTest: mock executor → PDF/DOCX/XLSX/PPTX/ZIP/صورة
