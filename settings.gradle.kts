rootProject.name = "PhoneBridge"

include(":core")
include(":relay")

// Android modules only build when an Android SDK is available.
val sdkFound = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkFound && file("android/app/build.gradle.kts").exists()) {
    include(":android:app")
}
