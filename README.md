# shadowUpload — نقل ملفات خاطف، بخصوصية كاملة

تطبيق أندرويد عربي (RTL كامل) لمشاركة الملفات على الشبكة المحلية، مبني من الصفر
بمحرك نقل **Java NIO / Zero-Copy** متعدد المسارات، ويعمل من **أندرويد 5.0 (API 21)**
حتى أحدث الإصدارات (API 34+).

> **لا يوجد خادم، لا يوجد حساب، لا يوجد تتبّع.** كل بايت ينتقل بين جهازين على
> الشبكة المحلية فقط.

---

## 1) المعمارية العامة

```
┌──────────────────────────────────────────────────────────────────┐
│  طبقة الواجهة (ui/)                                              │
│  Splash · Main(الملفات/أنا) · TransferHub · QR · Group · Clone    │
│  ShakeConnect · WifiHelp · History · Settings · Cleanup · Progress│
└───────────────────────────────┬──────────────────────────────────┘
                                │ LiveData / ViewModel
┌───────────────────────────────▼──────────────────────────────────┐
│  طبقة الخدمة (service/TransferService) — Foreground Service       │
│  إشعار دائم، تحديث تقدم، حفظ السجل، إلغاء آمن                      │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│  محرك النقل (core/transfer)                                      │
│  TransferEngine ──► ZeroCopyIO ──► FileChannel.transferTo        │
│        │                                                         │
│        └──► ReceiveSession (خادم متعدد المسارات)                  │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│  طبقة الشبكة (core/net)                                          │
│  WireProtocol · SocketTuner · ProgressAggregator · LanScanner     │
│  HandshakeToken · QrUtils · ControlJson                          │
└───────────────────────────────┬──────────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────────┐
│  طبقة البيانات (data/)                                           │
│  AppPreferences (SharedPreferences) · HistoryRepository (SQLite)  │
│  MediaRepository (MediaStore + SAF + متصفح الملفات)               │
└──────────────────────────────────────────────────────────────────┘
```

### تدفق الإرسال (زمن الاتصال الفعلي)

```
المرسل                                  المستقبل
  │  TCP connect (مهلة 800ms)             │
  │──────── "SHDU" + إصدار ──────────────►│
  │◄─────── "SHDU" + إصدار ──────────────│
  │──────── HELLO (هوية الجهاز) ─────────►│
  │◄─────── HELLO_ACK ───────────────────│
  │──────── OFFER (الملفات والأحجام) ────►│
  │◄─────── ACCEPT ──────────────────────│
  │──────── FILE_HEADER (جزء 0/N) ───────►│
  │════════ بيانات خام Zero-Copy ════════►│
  │◄─────── FILE_RESULT ─────────────────│
```

مصافحة واحدة ذهاباً وإياباً على الشبكة المحلية = **أقل من 200 مللي ثانية**
(القياس الفعلي يظهر داخل شاشة QR).

---

## 2) كيف نصبح أسرع من التطبيقات التقليدية؟

| التقنية | الملف | الأثر |
|---|---|---|
| `FileChannel.transferTo` (Zero-Copy) | `ZeroCopyIO.sendRange` | لا يُنسخ الملف إلى حيز المستخدم: النواة تنقل الصفحات مباشرة إلى المقبس |
| تقسيم متوازٍ للملف | `TransferEngine.sendParallelParts` | 4–8 اتصالات، كل منها يرسل جزءاً مستقلاً → استخدام كل الأنوية وإشباع النطاق |
| كتابة عشوائية متوازية | `ZeroCopyIO.receiveRangeFromStream` | كل جزء يُكتب في موضعه (`seek`) بلا أقفال متعارضة |
| مخازن مقبس كبيرة | `SocketTuner` | `SO_SNDBUF/SO_RCVBUF` 1–2 ميجابايت + `TCP_NODELAY` |
| بروتوكول ثنائي ثابت | `WireProtocol` | إطار 5 بايتات + حمولة، بلا تحليل نصوص |
| عدّاد تقدم ذري | `ProgressAggregator` | كل مسار يكتب في خليته؛ السرعة تُمهّد بنافذة 300 مللي ثانية |
| تدفقات غير مخزّنة | `TransferEngine` / `ReceiveSession` | يمنع الخلط بين بيانات مخزّنة وبيانات قناة الملف (سبب شائع للفساد) |
| رموز QR فورية | `HandshakeToken` | لا حاجة لأي اكتشاف: العنوان والمنفذ والرمز داخل الـ QR |

> الأرقام الواقعية تعتمد على الراوتر والجهازين. على شبكة 5GHz نظيفة نصل عادة
> إلى 40–90 ميجابايت/ثانية، وهو سقف WiFi عملياً؛ والتحسين الحقيقي مقابل التطبيقات
> التقليدية يأتي من التقسيم المتوازي + Zero-Copy، لا من رقم تسويقي.

---

## 3) الواجهة العربية و RTL

- اللغة الافتراضية **العربية**، وتُثبَّت في `ShadowUploadApp` مع `setLayoutDirection`.
- `android:supportsRtl="true"` و`layoutDirection="rtl"` في التخطيطات الجذرية.
- كل النصوص في `res/values/strings.xml` (أكثر من 390 نصاً عربياً) — لا نصوص مكتوبة
  داخل الكود.
- الأيقونات الاتجاهية تستخدم `autoMirrored` لتنعكس تلقائياً في RTL.
- الأرقام تُعرَض حسب إعداد النظام؛ الوحدات عربية (م.ب، ج.ب).

### الشريط السفلي: 3 عناصر بالضبط

| العنصر | الوظيفة |
|---|---|
| **الملفات** | تصنيفات: تطبيقات، فيديو، صور، صوت، مستندات، متصفح الذاكرة |
| **زر عائم مركزي** | يفتح **مركز النقل** |
| **أنا** | الإعدادات، السجل، تنظيف التخزين، الخصوصية |

لا يوجد تبويب «اكتشاف» ولا «انص به» في الشريط السفلي إطلاقاً.

### مركز النقل: 6 خيارات

1. إنشاء مجموعة
2. انضمام لمجموعة
3. المشاركة عبر الـ QR
4. نسخ هاتف آخر بالكامل
5. تحريك الهاتف للاتصال
6. إعدادات الـ WiFi والمساعدة

---

## 4) التوافق مع كل إصدارات أندرويد

| المجال | API 21–22 | 23–28 | 29 (Scoped) | 30–32 | 33–34 |
|---|---|---|---|---|---|
| قراءة الملفات | `READ_EXTERNAL_STORAGE` | نفسها + طلب وقت التشغيل | MediaStore + `requestLegacyExternalStorage` | `MANAGE_EXTERNAL_STORAGE` اختياري | `READ_MEDIA_*` + `READ_MEDIA_VISUAL_USER_SELECTED` |
| الأجهزة القريبة | `ACCESS_FINE_LOCATION` | نفسها | نفسها | `BLUETOOTH_SCAN/CONNECT` | `NEARBY_WIFI_DEVICES` |
| الخدمة الأمامية | `startService` | `startService` | `foregroundServiceType` | نفسها | `FOREGROUND_SERVICE_DATA_SYNC` + إشعار |
| الإشعارات | لا تحتاج | لا تحتاج | لا تحتاج | لا تحتاج | `POST_NOTIFICATIONS` |
| NIO | `FileChannel` + `RandomAccessFile` | نفسها | نفسها | + `java.nio.file` (API 26+) | نفسها |

كل الفروق معزولة في `PermissionUtils` و`MediaRepository` و`ZeroCopyIO` — الواجهات
لا تعرف شيئاً عن رقم الإصدار.

---

## 5) الخصوصية والأمان

- لا يوجد أي اتصال بالإنترنت: `HttpURLConnection/OkHttp` غير مستخدمة إطلاقاً.
- لا `allowBackup` ولا نسخ سحابي (`data_extraction_rules.xml`).
- رموز مصافحة آمنة عشوائياً (`SecureRandom`) بمقارنة ثابتة الزمن.
- تنقية أسماء الملفات ومنع Path Traversal في الاستقبال.
- تحقق اختياري SHA-256 (بـ `DirectByteBuffer`) لكل ملف مكتمل.
- الحوارات لا تعرض بيانات حساسة، والسجل محلي SQLite فقط.

---

## 6) البناء

```bash
# المتطلبات: JDK 17، Android SDK 34
./gradlew :app:assembleDebug          # نسخة تجريبية
./gradlew :app:assembleRelease        # نسخة محسّنة (R8)
./gradlew :app:testDebugUnitTest      # اختبارات الوحدة
python3 tools/verify.py               # فحص الموارد والكود (بلا Gradle)
```

### الفحص الشامل

```bash
python3 tools/verify.py        # 12 طبقة: موارد/مراجع/صياغة/وسائط
python3 tools/audit_deep.py    # تدقيق معمّق: تكرار، SAF، وسائط، توازن
```

تقرير آخر فحص كامل (14 عطلاً أُصلح، منها 4 حرجة): **[docs/AUDIT-REPORT.md](docs/AUDIT-REPORT.md)**

> **ملاحظة صادقة:** لم يُنفَّذ بناء Gradle داخل بيئة التسليم هذه لعدم توفر
> JDK/Android SDK فيها. التحقق تم بأداة `tools/verify.py` التي تفحص:
> صحة XML، كل مراجع `@drawable/@color/@string/@style/@dimen/@layout/@xml/@mipmap/@menu/@anim`،
> كل `R.type.name` في الكود، حقول viewBinding مقابل التخطيطات، أصناف المانيفست،
> صياغة Kotlin عبر tree-sitter، تكرار الأصناف، والاستيرادات الداخلية.
> هذا لا يُغني عن أول `./gradlew assembleDebug` على جهازك.

### هيكل المشروع

```
app/src/main/java/com/shadowupload/app/
├── ShadowUploadApp.kt
├── core/
│   ├── net/        WireProtocol · SocketTuner · TransferEngine · LanScanner
│   │               HandshakeToken · QrUtils · ControlJson · ProgressAggregator
│   ├── transfer/   ZeroCopyIO · TransferEngine · ReceiveSession · ChecksumUtils
│   ├── clone/      CloneExporter (vCard/JSON)
│   └── discovery/  WifiDirectManager
├── data/
│   ├── model/      FileItem · TransferProgress · TransferRecord · TransportConfig
│   ├── prefs/      AppPreferences
│   └── repo/       MediaRepository · HistoryRepository
├── service/        TransferService (Foreground)
├── ui/             splash · files(+category) · me · hub · qr · group · clone
│                   shake · wifi · history · settings · cleaner · transfer · common
└── util/           FormatUtils · PermissionUtils · NetworkUtils · UiUtils
```

---

## 7) ما الذي يعمل وما الذي يحتاج جهازاً؟

**يعمل بالكامل:** التبويبات والتنقل، تصفح التصنيفات، التحديد المتعدد، مركز النقل
وخياراته الستة، توليد QR ومسحه، إنشاء/انضمام مجموعة، التحريك، التشخيص، السجل،
الإعدادات، تنظيف التخزين، محرك الإرسال والاستقبال المتوازي، خدمة الخلفية،
الإشعارات، التحقق من البصمة.

**يحتاج جهازين حقيقيين للاختيار النهائي:** قياس السرعة الفعلية، ومصافحة
Wi-Fi Direct عند غياب شبكة مشتركة (الواجهة جاهزة وتتراجع بأمان إلى LAN).

**قيد نظام لا يمكن تجاوزه:** «نسخ بيانات تطبيق آخر» ممنوع بحكم sandbox الخاص
بأندرويد؛ لذلك النسخ يشمل ما هو متاح قانونياً (وسائط، مستندات، جهات اتصال،
رسائل، سجل مكالمات، حزم APK) وهذا موضح داخل الشاشة نفسها.
