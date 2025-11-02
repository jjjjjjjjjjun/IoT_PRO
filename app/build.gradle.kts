plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.iotcontrol"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.iotcontrol"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Firebase BOM (모든 Firebase 라이브러리 버전 통일)
    implementation(platform("com.google.firebase:firebase-bom:33.2.0"))  // 최신 버전 추천
    implementation("com.google.firebase:firebase-database-ktx")

    // Android 기본
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")  // 1.9.0 → 1.11.0 업그레이드
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.12.0")

    // Gemini AI SDK
    implementation("com.google.ai.client.generativeai:generativeai:0.3.0")

    // Coroutines (Gemini 비동기 처리)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Gson (Gemini JSON 파싱)
    implementation("com.google.code.gson:gson:2.10.1")
}