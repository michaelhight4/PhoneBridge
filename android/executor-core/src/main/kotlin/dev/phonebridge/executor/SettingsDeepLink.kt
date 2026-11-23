package dev.phonebridge.executor

/**
 * Constants for the Settings deep links the app opens for the user. Kept as plain strings here so
 * the executor module stays free of `android.*`; the app-side helper turns them into an `Intent`.
 *
 * Android deliberately does not let an app switch its own AccessibilityService on: the user has to
 * do it in Settings. The best we can do is take them straight to the right page.
 */
object SettingsDeepLink {

    /** Accessibility list, where the user enables the PhoneBridge service. */
    const val ACTION_ACCESSIBILITY_SETTINGS: String = "android.settings.ACCESSIBILITY_SETTINGS"

    /** Per-app permission/notification page. */
    const val ACTION_APPLICATION_DETAILS_SETTINGS: String = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** `data` value for [ACTION_APPLICATION_DETAILS_SETTINGS]. */
    fun packageUri(packageName: String): String = "package:$packageName"

    /**
     * Settings' accessibility page, scoped to our service where the OEM build supports the
     * fragment-argument shortcut. Values are passed as intent extras with these exact keys.
     */
    const val EXTRA_FRAGMENT_ARG_KEY: String = ":settings:fragment_args_key"
    const val EXTRA_SHOW_FRAGMENT_ARGS: String = ":settings:show_fragment_args"
    const val FRAGMENT_ARG_SERVICE: String = "accessibility_settings"
}
