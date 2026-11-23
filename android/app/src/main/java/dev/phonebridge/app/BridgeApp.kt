package dev.phonebridge.app

import android.app.Application

/**
 * Application entry point. Nothing here talks to the network yet: it only wires the plain-Kotlin
 * [dev.phonebridge.executor.CommandExecutor] to the Android device so a future transport service can
 * hand it requests.
 */
class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeRuntime.init(this)
    }
}
