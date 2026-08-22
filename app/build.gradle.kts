plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ubuntucontroller"
    compileSdk = 34

    signingConfigs {
        create("juzer") {
            storeFile = rootProject.file("juzer")
            storePassword = "Aa123456"
            keyAlias = "key0"
            keyPassword = "Aa123456"
        }
    }

    defaultConfig {
        applicationId = "com.ubuntucontroller"
        minSdk = 21
        targetSdk = 34
    versionCode = 16
    versionName = "1.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("juzer")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 无线调试免弹窗配对：muntashirakon/adb（mDNS 发现 + TLS SPAKE2 配对 + TLS 连接）
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}

if ("unitTestClasses" !in tasks.names) {
    tasks.register("unitTestClasses") {
        group = "verification"
        description = "Compile debug unit test sources compatibility alias."
        dependsOn("compileDebugUnitTestSources")
    }
}

// 兼容 Android Studio 旧构建流：部分 Studio 版本在 Build/Run 时仍按 AGP 7.0 以前的任务名
// 调用 `androidTestClasses`（AGP 8.x 已更名为 compile*AndroidTest* / assembleAndroidTest），
// 导致 "task ':app:androidTestClasses' not found" 报错。注册空操作兜底，避免 IDE 构建被卡住；
// 不影响 assembleDebug / assembleRelease 等真实构建任务。
if ("androidTestClasses" !in tasks.names) {
    tasks.register("androidTestClasses") {
        group = "verification"
        description = "No-op stub for legacy Android Studio instrumentation-test build trigger (AGP 8.x removed this task name)."
    }
}
