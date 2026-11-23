plugins {
    kotlin("jvm") version "2.3.0" apply false
    // Android Gradle plugin. Declared here (apply false) so the version is shared by every module,
    // and resolved through pluginManagement in settings.gradle.kts (google() first). It is only
    // ever applied by :android:app, which is included only when an Android SDK is installed, so an
    // SDK-less machine still configures :core / :relay / :android:executor-core without touching it.
    id("com.android.application") version "9.0.1" apply false
}
