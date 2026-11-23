pluginManagement {
    repositories {
        // AGP lives on Google's Maven repository; google() must come before mavenCentral().
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Only resolved when :android:app is included (i.e. when an Android SDK is present),
        // so an SDK-less machine can still run :core:test and :android:executor-core:test.
        id("com.android.application") version "9.0.1"
    }
}

rootProject.name = "PhoneBridge"

include(":core")
include(":relay")

// Plain Kotlin/JVM module: all command dispatch, permission and arg-parsing logic lives here so it
// builds and tests without an Android SDK. This is always part of the build.
include(":android:executor-core")

// Plain Kotlin/JVM module: the screen/service state logic (pairing countdown, approvals, bot list,
// activity log, relay choice, onboarding checklist). No android.* imports, so it is JVM-tested here.
include(":android:ui-core")

// Android modules only build when an Android SDK is available.
val sdkFound = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkFound && file("android/app/build.gradle.kts").exists()) {
    include(":android:app")
}

// End-to-end harness: a fake phone (real :core + :relay + the plain-Kotlin executor) driving a real
// relay server with bot clients. Plain JVM, so it runs without an Android SDK.
include(":e2e")
project(":e2e").projectDir = file("tests/e2e")
