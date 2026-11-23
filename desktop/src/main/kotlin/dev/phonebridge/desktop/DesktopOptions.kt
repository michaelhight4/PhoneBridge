package dev.phonebridge.desktop

import java.nio.file.Path

/** Command-line options for `gradlew.bat :desktop:run --args="..."`. */
data class DesktopOptions(
    val dataDir: Path? = null,
    /** Quit after this many milliseconds; 0 keeps the app running until the user quits. */
    val smokeMs: Long = 0,
    val showWindow: Boolean = true,
    val help: Boolean = false,
) {
    companion object {
        val USAGE = """
            PhoneBridge desktop agent

              --data-dir <path>   where identity, bots, settings and the log live
                                  (default: %APPDATA%\PhoneBridge)
              --smoke-ms <n>      start, then quit after n milliseconds (used by the build check)
              --no-window         start in the tray / indicator only
              --help              show this text

            The agent pairs bots with a QR code, short code or .pbpair file, shows each bot's permission
            level, names the bot in control in the tray and an always-on-top indicator, and can run the
            relay ("the heart") on this PC.
        """.trimIndent()

        fun parse(args: Array<String>): DesktopOptions {
            var options = DesktopOptions()
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--data-dir" -> {
                        index++
                        require(index < args.size) { "--data-dir needs a path" }
                        options = options.copy(dataDir = Path.of(args[index]))
                    }

                    "--smoke-ms" -> {
                        index++
                        require(index < args.size) { "--smoke-ms needs a number" }
                        options = options.copy(smokeMs = args[index].toLongOrNull()?.coerceAtLeast(0) ?: 0)
                    }

                    "--no-window" -> options = options.copy(showWindow = false)
                    "--help", "-h", "/?" -> options = options.copy(help = true)
                    else -> throw IllegalArgumentException("unknown option: $arg\n\n$USAGE")
                }
                index++
            }
            return options
        }
    }
}
