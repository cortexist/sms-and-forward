/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Answers the bridge's `location` command: where is the phone, cheaply, and
 * without a location permission.
 *
 * This feeds a PERIMETER test on the box ("is the human at the desk, or out"),
 * not navigation. The one signal that needs no permission at all is the phone's
 * own wifi address: if it lies in a subnet the box sits on, the phone is on the
 * same LAN as the box, which is "at home" with zero error. That is the whole
 * answer now. The app asks for no location permission (Play treats background
 * location as a feature in its own right, and this is not one), so the GPS fix
 * and the access-point BSSID that earlier versions reported are gone; the box
 * treats "not on the LAN" as unknown rather than away, and sends by default.
 *
 * The result is returned in the command's ack. The old fields stay in the
 * object as nulls so a box that still reads them sees "unknown", not an error.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

object PhoneLocation {
    private val LEGACY = listOf("lat", "lon", "acc_m", "ts", "provider", "wifi_ssid", "wifi_bssid")

    fun report(context: Context): JSONObject {
        val out = JSONObject()
        LEGACY.forEach { out.put(it, JSONObject.NULL) }
        out.put("permission", "not-requested")
        out.put("wifi_ips", JSONArray(wifiAddresses(context)))
        out.put("ts", System.currentTimeMillis() / 1000)
        return out
    }

    /** IPv4 addresses of the wifi interface; empty on mobile data or offline.
     *
     *  Read through java.net rather than LinkProperties: the framework method's
     *  signature differs between the compile SDK and some phones' connectivity
     *  module (NoSuchMethodError on getLinkAddresses), and an Error there killed
     *  the process with the whole command batch unacked. */
    fun wifiAddresses(context: Context): List<String> {
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
}
