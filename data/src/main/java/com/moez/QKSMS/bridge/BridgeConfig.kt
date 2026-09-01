/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * sms-bridge configuration.
 *
 * Stored in the app's default SharedPreferences and edited in Settings (the "SMS bridge"
 * section). The keys mirror Preferences in the domain module; this class reads them directly
 * because the data module cannot depend on domain, and the workers that need the config only
 * have a Context.
 *
 * A legacy sms-bridge.json in the app-specific external directory (the old adb-push install
 * path) is imported into SharedPreferences once, then renamed to .imported — after that the
 * file has no effect, so edit the settings, not the file.
 *
 * Absent or blank config means DISABLED. The forwarder stays inert until an endpoint and token
 * are configured, which is deliberate: QUIK ships with no network access at all, and turning
 * that on should be an explicit act rather than a side effect of installing a build.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
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
        const val KEY_ENABLED = "bridgeEnabled"
        const val KEY_ENDPOINT = "bridgeEndpoint"
        const val KEY_TOKEN = "bridgeToken"

        private const val LEGACY_FILE = "sms-bridge.json"

        val DISABLED = BridgeConfig(false, "", "")

        fun load(context: Context): BridgeConfig {
            return try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                if (listOf(KEY_ENABLED, KEY_ENDPOINT, KEY_TOKEN).none(prefs::contains)) {
                    importLegacyFile(context, prefs)
                }
                BridgeConfig(
                    enabled = prefs.getBoolean(KEY_ENABLED, false),
                    endpoint = prefs.getString(KEY_ENDPOINT, "")!!.trim(),
                    token = prefs.getString(KEY_TOKEN, "")!!.trim()
                )
            } catch (e: Exception) {
                // Never let bad config break message receipt. Failing closed is correct here:
                // the bridge is an add-on, the SMS app is the product.
                Timber.w(e, "sms-bridge: config unreadable, staying disabled")
                DISABLED
            }
        }

        private fun importLegacyFile(context: Context, prefs: SharedPreferences) {
            val f = File(context.getExternalFilesDir(null), LEGACY_FILE)
            if (!f.exists()) return
            try {
                val o = JSONObject(f.readText())
                prefs.edit()
                        .putBoolean(KEY_ENABLED, o.optBoolean("enabled", false))
                        .putString(KEY_ENDPOINT, o.optString("endpoint", ""))
                        .putString(KEY_TOKEN, o.optString("token", ""))
                        .apply()
                f.renameTo(File(f.parentFile, "$LEGACY_FILE.imported"))
                Timber.i("sms-bridge: imported legacy $LEGACY_FILE into settings")
            } catch (e: Exception) {
                // Leave the file in place so the problem is inspectable; stay disabled.
                Timber.w(e, "sms-bridge: legacy config unreadable, ignoring")
            }
        }
    }
}
