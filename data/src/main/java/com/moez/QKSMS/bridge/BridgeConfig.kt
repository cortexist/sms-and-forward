/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * sms-bridge configuration.
 *
 * Read from a JSON file in the app-specific external directory, so it can be installed with a
 * plain `adb push` and needs no extra permission, no settings UI, and no exported receiver
 * (an exported config receiver would be a code-injection surface on a device that handles 2FA):
 *
 *   adb push sms-bridge.json /sdcard/Android/data/dev.octoshrimpy.quik.debug/files/
 *
 *   { "enabled": true,
 *     "endpoint": "http://100.99.132.67:8090/sms",
 *     "token": "<the bearer token from ~/.sms2fa/token on the box>" }
 *
 * Absent or malformed config means DISABLED. The forwarder is inert until this file exists,
 * which is deliberate: QUIK ships with no network access at all, and turning that on should be
 * an explicit act rather than a side effect of installing a build.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.File

data class BridgeConfig(
    val enabled: Boolean,
    val endpoint: String,
    val token: String
) {
    val usable: Boolean
        get() = enabled && endpoint.isNotBlank() && token.isNotBlank()

    companion object {
        private const val FILE_NAME = "sms-bridge.json"

        @Volatile private var cached: BridgeConfig? = null
        @Volatile private var cachedAt: Long = 0
        private const val TTL_MS = 30_000L   // pick up an edited config without a reinstall

        val DISABLED = BridgeConfig(false, "", "")

        fun load(context: Context): BridgeConfig {
            val now = System.currentTimeMillis()
            cached?.let { if (now - cachedAt < TTL_MS) return it }

            val cfg = try {
                val f = File(context.getExternalFilesDir(null), FILE_NAME)
                if (!f.exists()) DISABLED else {
                    val o = JSONObject(f.readText())
                    BridgeConfig(
                        enabled = o.optBoolean("enabled", false),
                        endpoint = o.optString("endpoint", ""),
                        token = o.optString("token", "")
                    )
                }
            } catch (e: Exception) {
                // Never let bad config break message receipt. Failing closed is correct here:
                // the bridge is an add-on, the SMS app is the product.
                Timber.w(e, "sms-bridge: config unreadable, staying disabled")
                DISABLED
            }

            cached = cfg
            cachedAt = now
            return cfg
        }
    }
}
