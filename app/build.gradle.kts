plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ubuntucontroller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ubuntucontroller"
        minSdk = 21
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 使用 debug 签名，sideload 场景下可直接 adb install（不上架商店）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
}

// 兼容部分 IDE/脚本习惯调用的 :app:unitTestClasses task
if ("unitTestClasses" !in tasks.names) {
    tasks.register("unitTestClasses") {
        group = "verification"
        description = "编译 debug 单元测试源码（兼容别名）。"
        dependsOn("compileDebugUnitTestSources")
    }
}
