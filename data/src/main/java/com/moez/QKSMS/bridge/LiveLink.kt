/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Whether to hold a live link to the bridge right now.
 *
 * The queue is polled every fifteen minutes, which is fine for everything except
 * a human replying from the desktop and expecting the text to leave within a
 * second or two. That human is, by assumption, next to the phone -- so the fast
 * mode runs only while the phone is on the box's LAN AND the box says a desktop
 * app is open (`live.wanted`). Either condition ending ends the session and the
 * phone falls back to the queue. The phone still initiates every connection.
 *
 * "On the box's LAN" is decided first by the phone's own wifi address lying in
 * one of the subnets the box reports (`live.lan`), which needs no permission at
 * all; the access point BSSID (`live.bssids`) is the second test, because Android
 * masks the BSSID for background callers in more situations than it documents.
 *
 * The last hint and decision are kept for the `live_status` command, since the
 * only log this app writes lands in Downloads and is awkward to read from a box.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.octoshrimpy.quik.service.LiveBridgeService
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

object LiveLink {

    private const val PREFS = "sms-bridge-live"

    @Volatile var lastHint: JSONObject? = null

    /** Kept in SharedPreferences: a crash in the service kills the process and every static with it. */
    fun note(context: Context, key: String, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun noted(context: Context, key: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)

    fun shouldRun(context: Context, live: JSONObject?): Boolean {
        lastHint = live
        if (live == null) { note(context, "decision", "no live hint in response"); return false }
        if (!live.optBoolean("wanted", false)) { note(context, "decision", "not wanted (no desktop app open)"); return false }
        val ips = wifiAddresses(context)
        val lan = live.optJSONArray("lan").strings()
        val onLan = ips.any { ip -> lan.any { inCidr(ip, it) } }
        val here = PhoneLocation.wifiBssid(context)?.lowercase()
        // One access point, two radios: the 2.4 and 5 GHz BSSIDs differ in the last hex
        // digit, so match on the first five octets.
        val bssidMatch = here != null && live.optJSONArray("bssids").strings()
            .any { it.lowercase().dropLast(1) == here.dropLast(1) }
        note(context, "decision", "wanted; wifi ips=$ips lan=$lan onLan=$onLan; bssid=${here ?: "masked/none"} match=$bssidMatch")
        return onLan || bssidMatch
    }

    /** For the `live_status` command: everything the decision is made from, plus the outcome. */
    fun status(context: Context): JSONObject = JSONObject()
        .put("running", LiveBridgeService.running)
        .put("wifi_ips", JSONArray(wifiAddresses(context)))
        .put("wifi_bssid", PhoneLocation.wifiBssid(context) ?: JSONObject.NULL)
        .put("last_hint", lastHint ?: JSONObject.NULL)
        .put("last_decision", noted(context, "decision") ?: "never evaluated")
        .put("last_error", noted(context, "error") ?: JSONObject.NULL)
        .put("service_error", noted(context, "service_error") ?: JSONObject.NULL)

    /** IPv4 addresses of the wifi interface; empty on mobile data or offline.
     *
     *  Read through java.net rather than LinkProperties: the framework method's
     *  signature differs between the compile SDK and this phone's connectivity
     *  module (NoSuchMethodError on getLinkAddresses), and an Error there killed
     *  the process with the whole command batch unacked. */
    private fun wifiAddresses(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val net = cm.activeNetwork ?: return emptyList()
        val caps = cm.getNetworkCapabilities(net) ?: return emptyList()
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return emptyList()
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("tailscale") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun inCidr(ip: String, cidr: String): Boolean {
        val (net, bitsStr) = cidr.split("/").let { if (it.size == 2) it[0] to it[1] else return false }
        val bits = bitsStr.toIntOrNull() ?: return false
        val a = toInt(ip) ?: return false
        val n = toInt(net) ?: return false
        val mask = if (bits <= 0) 0 else (-1 shl (32 - bits))
        return (a and mask) == (n and mask)
    }

    private fun toInt(ip: String): Int? {
        val parts = ip.split(".").map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts.fold(0) { acc, p -> (acc shl 8) or p }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}
