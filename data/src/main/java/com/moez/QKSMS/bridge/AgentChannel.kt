/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * The agents' address. Messages from the box's agents appear in an ordinary
 * conversation whose peer is AGENTS -- alphanumeric ON PURPOSE. It cannot be a
 * phone number, so nothing typed into that thread can ever reach a carrier: a
 * reply is marked sent locally and forwarded to the bridge as an outbound record
 * (MessageRepositoryImpl.sendMessage), which is the human -> agent channel.
 *
 * Nothing addressed to or from AGENTS is forwarded as incoming mail or backfilled,
 * and the bridge never extracts a 2FA code from it, so an agent quoting a
 * confirmation number cannot satisfy another agent's wait for a code.
 */
package dev.octoshrimpy.quik.bridge

object AgentChannel {
    const val ADDRESS = "AGENTS"

    fun isAgent(address: String?): Boolean = address?.trim().equals(ADDRESS, ignoreCase = true)
}
