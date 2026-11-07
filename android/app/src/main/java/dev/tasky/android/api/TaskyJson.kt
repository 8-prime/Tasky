package dev.tasky.android.api

import kotlinx.serialization.json.Json

/**
 * The one Json configuration for talking to the server and for storing ops locally.
 *
 * - `ignoreUnknownKeys`: newer servers may add fields.
 * - Default `classDiscriminator = "type"`: matches the server's `{"type": ..., "value": ...}` unions.
 * - `explicitNulls` stays on, so `{"type": "category", "value": null}` (clearing a field) is sent.
 * - `encodeDefaults` stays off, so optional fields with a null default are left out, which the
 *   server accepts.
 */
val TaskyJson: Json = Json {
    ignoreUnknownKeys = true
}
