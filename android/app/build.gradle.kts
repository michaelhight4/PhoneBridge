// The PhoneBridge Android app. Android Gradle plugin 9.0.1 (Gradle >= 9.1) with its built-in Kotlin
// support, so no separate Kotlin Android plugin is applied. This module is only included in
// settings.gradle.kts when an Android SDK is present; all testable logic lives in
// :android:executor-core and :android:ui-core so the JVM tests run on machines without an SDK.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.phonebridge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.phonebridge.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // Pure Kotlin/JVM: request dispatch, permission checks, UI-tree ids, control arbitration.
    implementation(project(":android:executor-core"))
    // Pure Kotlin/JVM: pairing countdown, approvals, bot list, activity log, relay choice, onboarding.
    implementation(project(":android:ui-core"))
    // Protocol types, pairing manager, Noise sessions and the JSON-file bot registry.
    implementation(project(":core"))
    // Embedded relay and the reconnecting relay client.
    implementation(project(":relay"))
    // QR encoding for the pairing screen (Apache-2.0, ZXing core only).
    implementation("com.google.zxing:core:3.5.3")
}
