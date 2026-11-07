package dev.tasky.android.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrl {
    /**
     * Turns what a user types into a base URL without trailing slash or API prefix:
     * `tasky.example.com/` → `https://tasky.example.com`,
     * `http://10.0.2.2:8080/api/v1` → `http://10.0.2.2:8080`.
     */
    fun normalize(input: String): String {
        var url = input.trim()
        if (!url.contains("://")) url = "https://$url"
        url = url.trimEnd('/')
        if (url.endsWith("/api/v1")) url = url.removeSuffix("/api/v1").trimEnd('/')
        return url
    }

    /** Null if [input] can't be a server URL. */
    fun parse(input: String): String? {
        if (input.isBlank()) return null
        val url = normalize(input)
        val parsed = url.toHttpUrlOrNull() ?: return null
        return if (parsed.host.isEmpty()) null else url
    }
}
