// build.gradle.kts (Project-level)
plugins {
    // 필요 시 추가
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.3.0")  // 8.13.0은 아직 없음 → 8.3.0 사용
        classpath("com.google.gms:google-services:4.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")  // Kotlin 플러그인 필수!
    }
}

// 모든 서브 프로젝트에 적용
subprojects {
    // 필요 시 공통 설정
}