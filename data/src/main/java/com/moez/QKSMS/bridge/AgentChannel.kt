/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Agent addresses. Every agent on the box is a conversation of its own, whose peer address
 * ends in "@agents" -- "ides@agents", "chief@agents" -- alphanumeric ON PURPOSE: it cannot be
 * a phone number, so nothing typed into such a thread can ever reach a carrier. A reply is
 * marked sent locally and forwarded to the bridge as an outbound record addressed to that
 * agent (MessageRepositoryImpl.sendMessage), which is the human -> agent channel.
 *
 * "AGENTS" is the original, single thread from before agents had identities; it is kept as
 * the legacy alias of the chief.
 *
 * Nothing addressed to or from an agent is forwarded as incoming mail or backfilled, and the
 * bridge never extracts a 2FA code from it.
 */
package dev.octoshrimpy.quik.bridge

object AgentChannel {
    const val ADDRESS = "AGENTS"          // legacy chief thread
    const val CHIEF = "chief@agents"
    private const val DOMAIN = "@agents"

    fun isAgent(address: String?): Boolean {
        val a = address?.trim()?.lowercase() ?: return false
        return a == ADDRESS.lowercase() || a.endsWith(DOMAIN)
    }
}
