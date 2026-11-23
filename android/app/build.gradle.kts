// The PhoneBridge Android app. Android Gradle plugin 9.0.1 (Gradle >= 9.1) with its built-in Kotlin
// support, so no separate Kotlin Android plugin is applied. This module is only included in
// settings.gradle.kts when an Android SDK is present; all testable logic lives in
// :android:executor-core so the JVM tests run on machines without an SDK.
plugins {
    id("com.android.application")
}

// Needed for the Shizuku AARs. The plain-JVM modules declare their own repositories; this module
// previously only consumed a project dependency.
repositories {
    google()
    mavenCentral()
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

    // Shizuku (MIT): the privileged helper API and its binder provider. Only the Full-level
    // commands need it; without Shizuku installed or running those commands answer
    // shizuku_unavailable and everything else keeps working. `aidl` carries IShizukuService and
    // IRemoteProcess, which the app uses to start a shell process (see ShizukuProcess).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
