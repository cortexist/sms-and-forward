/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 */
package dev.octoshrimpy.quik.feature.settings

import android.net.Uri

/**
 * The pairing code SMS desktop shows as a QR: `smsforward://pair?endpoint=<url>&token=<token>`.
 * The endpoint is the full URL the phone posts messages to (…/sms). Both parts are
 * URL-encoded by the desktop and decoded by the Uri parser here.
 */
object BridgePairing {
    const val SCHEME = "smsforward"
    const val HOST = "pair"

    /** (endpoint, token), or null when the text is not a pairing code. */
    fun parse(text: String): Pair<String, String>? {
        val uri = try { Uri.parse(text.trim()) } catch (e: Exception) { return null }
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true)) return null
        val endpoint = uri.getQueryParameter("endpoint")?.trim().orEmpty()
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        if (token.isBlank()) return null
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) return null
        return endpoint to token
    }
}
