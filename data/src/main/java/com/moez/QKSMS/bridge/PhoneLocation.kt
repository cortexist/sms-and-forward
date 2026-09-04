/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Answers the bridge's `location` command: where is the phone, cheaply.
 *
 * This feeds a PERIMETER test on the box ("is the human within a few hundred
 * metres of the desk, or out"), not navigation, so the cheapest answer wins:
 * the access point the phone is on, then the freshest last-known fix from any
 * provider, and only if that is stale a single bounded request. The result is
 * returned in the command's ack; every field is null when unknown, and
 * `permission` says why, so the box can tell "not granted" from "no fix".
 *
 * Location permission is granted by the user in Android's app settings (there is
 * no in-app prompt for this): "Allow all the time" is what lets a WorkManager job
 * -- background, by Android's definition -- read a fix and the wifi BSSID at all
 * on Android 10+. Without it the answer is honest nulls, never a crash.
 */
package dev.octoshrimpy.quik.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PhoneLocation {
    private const val FRESH_MS = 10 * 60 * 1000L   // a last-known fix younger than this is good enough
    private const val WAIT_MS = 20_000L            // one bounded request when it is not
    private val FIELDS = listOf("lat", "lon", "acc_m", "ts", "provider", "wifi_ssid", "wifi_bssid")

    fun report(context: Context): JSONObject {
        val out = JSONObject()
        FIELDS.forEach { out.put(it, JSONObject.NULL) }
        val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        out.put("permission", when {
            !(fine || coarse) -> "none"
            !background -> "foreground-only"
            fine -> "fine"
            else -> "coarse"
        })
        if (!(fine || coarse)) return out

        try {
            wifi(context, out)
        } catch (e: Exception) {
            Timber.v("sms-bridge: wifi info unavailable (${e.javaClass.simpleName})")
        }
        val loc = try {
            best(context, fine)
        } catch (e: SecurityException) {
            null
        }
        if (loc != null) {
            out.put("lat", loc.latitude)
            out.put("lon", loc.longitude)
            if (loc.hasAccuracy()) out.put("acc_m", loc.accuracy.toDouble())
            out.put("ts", loc.time / 1000)
            out.put("provider", loc.provider ?: JSONObject.NULL)
        }
        return out
    }

    /** The BSSID the phone is on, lowercase, or null when unknown or not permitted. */
    fun wifiBssid(context: Context): String? = try {
        JSONObject().also { wifi(context, it) }.let { if (it.isNull("wifi_bssid")) null else it.optString("wifi_bssid") }
    } catch (e: Exception) {
        null
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** The access point the phone is on. Android masks the BSSID as 02:00:00:00:00:00
     *  when location access is not allowed in this context; that is reported as null. */
    @Suppress("DEPRECATION")
    private fun wifi(context: Context, out: JSONObject) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val info = wm.connectionInfo ?: return
        val bssid = info.bssid?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
            ?: return
        out.put("wifi_bssid", bssid)
        val ssid = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        out.put("wifi_ssid", ssid ?: JSONObject.NULL)
    }

    private fun best(context: Context, fine: Boolean): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = lm.getProviders(true).filter { fine || it != LocationManager.GPS_PROVIDER }
        var best: Location? = null
        for (p in providers) {
            val l = lm.getLastKnownLocation(p) ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        val known = best
        if (known != null && System.currentTimeMillis() - known.time <= FRESH_MS) return known

        // Stale or absent: ask once, briefly, and settle for what we had if nothing comes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                fine && LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
                else -> providers.firstOrNull()
            } ?: return known
            val latch = CountDownLatch(1)
            var fresh: Location? = null
            val signal = CancellationSignal()
            val exec = Executors.newSingleThreadExecutor()
            try {
                lm.getCurrentLocation(provider, signal, exec) { l -> fresh = l; latch.countDown() }
                if (!latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) signal.cancel()
            } catch (e: Exception) {
                Timber.v("sms-bridge: getCurrentLocation failed (${e.javaClass.simpleName})")
            } finally {
                exec.shutdown()
            }
            fresh?.let { return it }
        }
        return known
    }
}
