# قواعد ProGuard/R8 لمشروع shadowUpload

# الحفاظ على أسماء واجهات الاتصال الداخلي للـ Kotlin
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class kotlin.Metadata { public <methods>; }

# خدمات Android المعلنة في المانيفست تُستدعى بالانعكاس من النظام
-keep class com.shadowupload.app.service.** { *; }

# منع إزالة فئات نقل البيانات (Data) المستخدمة في التفكيك اللاحق
-keep class com.shadowupload.app.data.model.** { *; }

# Material Components
-dontwarn com.google.android.material.**
