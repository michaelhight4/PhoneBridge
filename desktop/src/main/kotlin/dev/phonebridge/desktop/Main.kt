package dev.phonebridge.desktop

import kotlin.system.exitProcess

/**
 * Entry point for `gradlew.bat :desktop:run`.
 *
 * Runs the tray app until the user quits. `--smoke-ms` starts everything, waits, then quits cleanly so
 * the build check can prove the app comes up without errors and without leaving it running.
 */
fun main(args: Array<String>) {
    val options = try {
        DesktopOptions.parse(args)
    } catch (e: IllegalArgumentException) {
        println(e.message)
        exitProcess(2)
    }
    if (options.help) {
        println(DesktopOptions.USAGE)
        return
    }
    val app = DesktopApp(options)
    try {
        app.start()
    } catch (e: Exception) {
        println("PhoneBridge could not start: ${e.message}")
        e.printStackTrace()
        exitProcess(2)
    }
    if (options.smokeMs > 0) {
        Thread.sleep(options.smokeMs)
        println("smoke run finished after ${options.smokeMs} ms; quitting")
        app.quit(0)
        Thread.sleep(1_000)
        exitProcess(0)
    }
    app.awaitQuit()
}
