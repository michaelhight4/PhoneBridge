package dev.phonebridge.core.util

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration every PhoneBridge component must use.
 *
 * - `encodeDefaults = true` so version fields and booleans are always on the wire, which keeps relays
 *   and bots that only forward bytes from having to guess defaults.
 * - `explicitNulls = false` so optional fields disappear instead of becoming `"x": null`, matching the
 *   shapes in docs/PROTOCOL.md.
 * - `ignoreUnknownKeys = true` so a newer peer can add fields without breaking an older one.
 */
val PbJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = false
}

/** Same settings, but indented: used for `.pbpair` files and the on-disk bot registry. */
val PbJsonPretty: Json = Json(PbJson) { prettyPrint = true }
