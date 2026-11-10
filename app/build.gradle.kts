// إعدادات وحدة التطبيق shadowUpload
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shadowupload.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shadowupload.app"
        // الحد الأدنى: أندرويد 5.0 (API 21) كما هو مطلوب
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // دعم كامل لـ RTL (العربية) دون الحاجة لملفات موارد منفصلة
        resConfigs += listOf("ar", "en")
        vectorDrawables.useSupportLibrary = true

        // أجهزة API 21–23 قد تحتاج multidex بلا تصغير:
        // نُبقي التصغير مؤجلاً للنسخة النهائية، والمكتبات قليلة هنا
        multiDexEnabled = true
    }

    signingConfigs {
        // التوقيع للتجربة؛ في الإصدار النهائي تُستخدم مفاتيح خاصة خارج المستودع
        create("debugLocal") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // تفعيل واجهات Java NIO الجديدة على API 21+
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // نواة أندرويد وواجهات حديثة تعمل على API 21
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.6")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // multidex لأجهزة أندرويد 5 (API 21) عند تجاوز 64 ألف دالة
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // مكونات واجهة Material 3 (تعمل مع AppCompat على API 21)
    implementation("com.google.android.material:material:1.12.0")

    // كوروتينات للعمليات غير المتزامنة
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // توليد وقراءة رموز QR (بدون خدمات سحابية)
    implementation("com.google.zxing:core:3.5.3")

    // كاميرا حديثة تعمل من API 21 لمسح رموز QR
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // أدوات مساعدة لـ NIO المتوافقة مع الإصدارات القديمة
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
