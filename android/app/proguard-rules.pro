# PhoneBridge release rules. R8 is off for now (isMinifyEnabled = false); these keep the
# accessibility service and its config reachable once minification is switched on.
-keep class dev.phonebridge.app.BridgeAccessibilityService { *; }
-keep class dev.phonebridge.app.BridgeApp { *; }
-keepclassmembers class dev.phonebridge.app.** {
    <init>(...);
}
