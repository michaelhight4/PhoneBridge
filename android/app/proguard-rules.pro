# PhoneBridge release rules. R8 is off for now (isMinifyEnabled = false); these keep the
# accessibility service, the Shizuku bridge and Shizuku's own provider/binder classes reachable once
# minification is switched on.
-keep class dev.phonebridge.app.BridgeAccessibilityService { *; }
-keep class dev.phonebridge.app.BridgeApp { *; }
-keep class dev.phonebridge.app.ShizukuBridge { *; }
-keepclassmembers class dev.phonebridge.app.** {
    <init>(...);
}

# Shizuku API is called through its provider/binder; its names must survive shrinking.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**
