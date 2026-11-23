package dev.phonebridge.ui

/** The three things the user must switch on before the app can work fully. */
enum class OnboardingStep { ACCESSIBILITY, NOTIFICATIONS, BATTERY }

data class OnboardingStatus(
    val accessibilityEnabled: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryOptimisationExempt: Boolean = false,
)

data class OnboardingItem(
    val step: OnboardingStep,
    val title: String,
    val detail: String,
    val done: Boolean,
)

/**
 * The onboarding checklist: accessibility service, notification permission and battery
 * optimisation exemption. Android shows its own consent dialogs for all three; the app only reports
 * their state and deep-links to the right settings page.
 */
object Onboarding {
    fun items(status: OnboardingStatus): List<OnboardingItem> = listOf(
        OnboardingItem(
            step = OnboardingStep.ACCESSIBILITY,
            title = "Accessibility service",
            detail = "Lets paired bots read the screen and tap, swipe and type. Android asks you to enable it.",
            done = status.accessibilityEnabled,
        ),
        OnboardingItem(
            step = OnboardingStep.NOTIFICATIONS,
            title = "Notifications",
            detail = "Shows which bot is in control and asks before a new bot is allowed.",
            done = status.notificationsGranted,
        ),
        OnboardingItem(
            step = OnboardingStep.BATTERY,
            title = "Battery optimisation",
            detail = "Keeps the encrypted connection alive when the screen is off.",
            done = status.batteryOptimisationExempt,
        ),
    )

    fun isComplete(status: OnboardingStatus): Boolean = items(status).all { it.done }

    fun firstMissing(status: OnboardingStatus): OnboardingItem? = items(status).firstOrNull { !it.done }
}
