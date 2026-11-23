package dev.phonebridge.app

/**
 * Holds the live [BridgeAccessibilityService]. Android may bind and unbind the service at any time
 * (the user turns it off, the system restarts it, a battery saver kicks in), so everything that needs
 * the service goes through this single volatile reference instead of caching it.
 */
object AccessibilityBridge {

    @Volatile
    var service: BridgeAccessibilityService? = null
        private set

    /** True while the accessibility service is bound, i.e. device control actually works. */
    val isConnected: Boolean get() = service != null

    fun attach(service: BridgeAccessibilityService) {
        this.service = service
    }

    fun detach(service: BridgeAccessibilityService) {
        if (this.service === service) this.service = null
    }
}
