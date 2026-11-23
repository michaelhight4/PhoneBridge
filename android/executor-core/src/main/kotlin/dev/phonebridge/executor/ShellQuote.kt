package dev.phonebridge.executor

/**
 * Quoting and validation for every string that becomes part of a `sh` script.
 *
 * Shizuku runs the script as the **shell** user, so a bot-supplied path or settings key must never be
 * able to add a command. Everything that reaches a script goes through [quote] (single quotes, with
 * `'\''` for embedded quotes) and, where the value has a meaningful shape, one of the validators
 * below, which throw [BadArgsException] instead of guessing.
 */
object ShellQuote {

    /** Longest shell script we are willing to hand to Shizuku (an argument, not a file). */
    const val MAX_SCRIPT_CHARS: Int = 60_000

    /** Longest filesystem path accepted from a bot. */
    const val MAX_PATH_CHARS: Int = 4096

    /** Longest settings key/value accepted from a bot. */
    const val MAX_SETTINGS_KEY_CHARS: Int = 256
    const val MAX_SETTINGS_VALUE_CHARS: Int = 4096

    /** Longest shell `command` accepted from a bot. */
    const val MAX_SHELL_COMMAND_CHARS: Int = 32_000

    private val SETTINGS_KEY = Regex("[A-Za-z0-9_.:\\-]+")

    /** Wraps [value] in single quotes so `sh` treats every byte literally. */
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "shell arguments cannot contain a NUL byte" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * A filesystem path as sent by `files`: absolute, one line, no NUL. Relative paths are rejected
     * so a shell `cd` in an earlier command cannot silently change what we touch.
     */
    fun validatePath(field: String, value: String): String {
        if (value.isEmpty()) throw BadArgsException(field, "path must not be empty")
        if (value.length > MAX_PATH_CHARS) throw BadArgsException(field, "path is longer than $MAX_PATH_CHARS characters")
        if (!value.startsWith("/")) throw BadArgsException(field, "path must be absolute (start with /), got '$value'")
        rejectControlCharacters(field, value)
        return value
    }

    /** A `settings` key: letters, digits, `_`, `.`, `:` and `-` only. */
    fun validateSettingsKey(value: String): String {
        if (value.isEmpty()) throw BadArgsException("key", "key must not be empty")
        if (value.length > MAX_SETTINGS_KEY_CHARS) {
            throw BadArgsException("key", "key is longer than $MAX_SETTINGS_KEY_CHARS characters")
        }
        if (!SETTINGS_KEY.matches(value)) {
            throw BadArgsException("key", "key may only contain letters, digits, '_', '.', ':' and '-'")
        }
        return value
    }

    /** A `settings` value: any single line, because `settings put` takes one string. */
    fun validateSettingsValue(value: String): String {
        if (value.length > MAX_SETTINGS_VALUE_CHARS) {
            throw BadArgsException("value", "value is longer than $MAX_SETTINGS_VALUE_CHARS characters")
        }
        rejectControlCharacters("value", value)
        return value
    }

    /** A shell `command`: one or more characters, bounded in length. */
    fun validateShellCommand(value: String): String {
        if (value.isBlank()) throw BadArgsException("command", "command must not be blank")
        if (value.length > MAX_SHELL_COMMAND_CHARS) {
            throw BadArgsException("command", "command is longer than $MAX_SHELL_COMMAND_CHARS characters")
        }
        return value
    }

    /** Guards the built script size itself, so we fail with `bad_args` instead of an exec error. */
    fun checkScriptLength(script: String) {
        if (script.length > MAX_SCRIPT_CHARS) {
            throw BadArgsException("path", "the resulting shell command is too long")
        }
    }

    /**
     * The split name sent to `pm install-write`: a basename without separators, so a bot-supplied
     * `name` cannot escape the install session.
     */
    fun safeApkName(name: String?): String {
        val flattened = (name ?: "").trim().replace('/', '_').replace('\\', '_')
        val cleaned = flattened
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_', '.', '-')
        val base = cleaned.ifEmpty { DEFAULT_APK_NAME.removeSuffix(".apk") }
        return if (base.endsWith(".apk", ignoreCase = true)) base else "$base.apk"
    }

    private fun rejectControlCharacters(field: String, value: String) {
        if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            throw BadArgsException(field, "must be a single line without NUL bytes")
        }
    }

    const val DEFAULT_APK_NAME: String = "base.apk"
}
