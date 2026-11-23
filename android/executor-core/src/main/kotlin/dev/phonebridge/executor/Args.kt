package dev.phonebridge.executor

/** Thrown by [Args] when a required argument is missing or has the wrong type. */
class BadArgsException(val field: String, reason: String) :
    IllegalArgumentException("bad_args: $field: $reason")

/**
 * Typed reader over a decoded request `args` object.
 *
 * JSON has one number type, so numbers may arrive as [Int], [Long], [Double] or (from some
 * transports) as decimal strings; every accessor normalises them and reports `bad_args` instead of
 * throwing a [ClassCastException] into the transport loop.
 */
class Args(private val values: Map<String, Any?>) {

    constructor(vararg pairs: Pair<String, Any?>) : this(mapOf(*pairs))

    fun has(name: String): Boolean = values[name] != null

    fun string(name: String): String? {
        val value = values[name] ?: return null
        return value as? String
            ?: throw BadArgsException(name, "expected a string, got ${describe(value)}")
    }

    fun requiredString(name: String): String =
        string(name)?.takeIf { it.isNotEmpty() }
            ?: throw BadArgsException(name, "required string is missing or empty")

    fun int(name: String): Int? {
        val value = values[name] ?: return null
        return toLong(name, value)?.toInt()
            ?: throw BadArgsException(name, "expected an integer, got ${describe(value)}")
    }

    fun requiredInt(name: String): Int =
        int(name) ?: throw BadArgsException(name, "required integer is missing")

    fun long(name: String): Long? {
        val value = values[name] ?: return null
        return toLong(name, value) ?: throw BadArgsException(name, "expected an integer, got ${describe(value)}")
    }

    fun longOr(name: String, default: Long): Long = long(name) ?: default

    fun intOr(name: String, default: Int): Int = int(name) ?: default

    fun boolean(name: String): Boolean? {
        val value = values[name] ?: return null
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw BadArgsException(name, "expected a boolean, got \"$value\"")
            }
            else -> throw BadArgsException(name, "expected a boolean, got ${describe(value)}")
        }
    }

    fun booleanOr(name: String, default: Boolean): Boolean = boolean(name) ?: default

    /** Alternate key names, e.g. `node_id` vs `nodeId`, so adapters cannot get this wrong silently. */
    fun stringAny(vararg names: String): String? {
        for (name in names) {
            val value = string(name)
            if (value != null) return value
        }
        return null
    }

    private fun toLong(name: String, value: Any): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> if (value % 1.0 == 0.0) value.toLong() else null
        is Float -> if (value % 1.0f == 0.0f) value.toLong() else null
        is String -> value.trim().toLongOrNull()
        else -> throw BadArgsException(name, "expected an integer, got ${describe(value)}")
    }

    private fun describe(value: Any): String = value::class.simpleName ?: "value"
}
