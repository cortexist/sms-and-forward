/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * What the phone knows about each agent: display name and the two avatar parameters, colour
 * and shape, as carried by every `notify` command. The source of truth is the agents address
 * book on the box (Radicale); this is the phone's cache of it, keyed by agent address, so an
 * agent's thread shows its name and face even before the next message.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

data class AgentIdentity(val address: String, val name: String, val color: Int, val shape: Int)

object AgentRegistry {
    private const val PREFS = "sms-bridge-agents"

    fun record(context: Context, address: String, name: String?, color: String?, shape: Int?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = address.trim().lowercase()
        val old = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (!name.isNullOrBlank()) old.put("name", name)
        if (!color.isNullOrBlank()) old.put("color", color)
        if (shape != null) old.put("shape", shape)
        prefs.edit().putString(key, old.toString()).apply()
    }

    fun get(context: Context, address: String?): AgentIdentity? {
        if (!AgentChannel.isAgent(address)) return null
        val key = address!!.trim().lowercase()
        val o = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val color = o?.optString("color")?.takeIf { it.isNotBlank() }?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        return AgentIdentity(
            address = address.trim(),
            name = o?.optString("name")?.takeIf { it.isNotBlank() } ?: defaultName(key),
            color = color ?: 0xFF7AA2F7.toInt(),
            shape = o?.optInt("shape", -1)?.takeIf { it >= 0 } ?: -1)   // -1: follow the control-shape setting
    }

    private fun defaultName(key: String) = when (key) {
        "agents", AgentChannel.CHIEF -> "Chief"
        else -> key.substringBefore("@").replaceFirstChar { it.uppercase() } + " agent"
    }
}
