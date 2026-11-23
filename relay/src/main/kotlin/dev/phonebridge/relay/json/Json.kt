package dev.phonebridge.relay.json

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Small Gson helpers. Envelopes are parsed field by field; the encrypted payload is never decoded. */
object Json {
    val gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseObject(text: String): JsonObject? = try {
        val element = JsonParser.parseString(text)
        if (element.isJsonObject) element.asJsonObject else null
    } catch (_: Exception) {
        null
    }

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
        val out = JsonObject()
        for ((key, value) in pairs) {
            when (value) {
                null -> Unit
                is JsonElement -> out.add(key, value)
                is String -> out.addProperty(key, value)
                is Number -> out.addProperty(key, value)
                is Boolean -> out.addProperty(key, value)
                is Map<*, *> -> {
                    val nested = JsonObject()
                    for ((k, v) in value) {
                        if (k is String) {
                            when (v) {
                                null -> Unit
                                is JsonElement -> nested.add(k, v)
                                is String -> nested.addProperty(k, v)
                                is Number -> nested.addProperty(k, v)
                                is Boolean -> nested.addProperty(k, v)
                                else -> nested.addProperty(k, v.toString())
                            }
                        }
                    }
                    out.add(key, nested)
                }
                else -> out.addProperty(key, value.toString())
            }
        }
        return out
    }

    fun string(obj: JsonObject, name: String): String? {
        val element = obj.get(name) ?: return null
        if (element.isJsonNull) return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    fun long(obj: JsonObject, name: String): Long? {
        val element = obj.get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return try {
            element.asLong
        } catch (_: Exception) {
            null
        }
    }

    fun objOrNull(obj: JsonObject, name: String): JsonObject? {
        val element = obj.get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }
}
