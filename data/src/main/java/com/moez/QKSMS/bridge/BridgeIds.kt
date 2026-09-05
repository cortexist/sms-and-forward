/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * The id a message carries on the bridge: "sms:<provider row id>" / "mms:<provider row id>".
 *
 * NOT the Realm id. QUIK renumbers its Realm ids from 1 on every full sync (KeyManager
 * .reset()), which a fresh install performs, so a Realm-based id collided with the
 * bridge's archive of the previous numbering: forwards were dropped as duplicates and a
 * status update landed on a stranger's message. The Telephony provider's row id is the
 * one identity that survives a resync. The bridge additionally keys on (id, ts), so even
 * a row id the provider reuses after a deletion cannot collide.
 */
package dev.octoshrimpy.quik.bridge

import dev.octoshrimpy.quik.model.Message
import io.realm.Realm

object BridgeIds {

    fun of(m: Message): String {
        val kind = if (m.type == Message.TYPE_MMS) "mms" else "sms"
        // contentId is 0 when the provider insert failed; fall back to the Realm id, marked.
        return if (m.contentId > 0) "$kind:${m.contentId}" else "$kind:r${m.id}"
    }

    /** The Realm message a bridge id names, or null. Accepts the archive's "@ts" suffix
     *  and the legacy Realm-id form ("sms:r<id>", or a bare id from before this scheme). */
    fun resolve(id: String): Message? {
        val base = id.substringBefore('@')
        val kind = base.substringBefore(':', "")
        val rest = base.substringAfter(':')
        val type = if (kind == "mms") Message.TYPE_MMS else Message.TYPE_SMS
        return Realm.getDefaultInstance().use { realm ->
            val found = if (rest.startsWith("r")) {
                rest.drop(1).toLongOrNull()?.let { realm.where(Message::class.java).equalTo("id", it).findFirst() }
            } else {
                rest.toLongOrNull()?.let {
                    realm.where(Message::class.java).equalTo("contentId", it).equalTo("type", type).findFirst()
                }
            }
            found?.let { realm.copyFromRealm(it) }
        }
    }
}
