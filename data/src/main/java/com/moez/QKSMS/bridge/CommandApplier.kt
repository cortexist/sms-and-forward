/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Applies one sms-bridge command. Shared by CommandWorker (the periodic and
 * piggybacked drain) and LiveBridgeService (the long-poll used while the
 * desktop is in front of the human), so the two paths cannot drift apart.
 *
 * Built by hand from the repositories rather than injected: the worker factory
 * assigns fields manually and the service uses AndroidInjection, and both have
 * exactly the three dependencies this needs.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.worker.BackfillWorker
import dev.octoshrimpy.quik.worker.ForwardMessageWorker
import dev.octoshrimpy.quik.worker.ReceiveSmsWorker
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class CommandApplier(
    private val context: Context,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val prefs: Preferences
) {

    /** The command's outcome for the ack: a string, or a JSON object for commands that are questions. */
    fun apply(op: String, args: JSONObject, base: String, token: String, client: OkHttpClient): Any = when (op) {
        "delete_messages" -> {
            val ids = args.longsFromIds("ids")
            messageRepo.deleteMessages(ids)
            "deleted ${ids.size}"
        }
        "delete_conversations" -> {
            // Addressable either way. getConversation (not getOrCreate) on purpose:
            // deleting a conversation must never create one as a side effect.
            val byId = args.longs("threads")
            val byAddr = args.strings("addrs").mapNotNull { conversationRepo.getConversation(listOf(it))?.id }
            val t = (byId + byAddr).distinct()
            if (t.isEmpty()) "no matching conversation" else {
                conversationRepo.deleteConversations(*t.toLongArray())
                "deleted ${t.size} conversation(s)"
            }
        }
        // FIFO trim. Evaluated here because only the phone holds the complete chain.
        "delete_old_messages" -> {
            val days = args.optInt("days", 0)
            if (days <= 0) "refused: days must be > 0" else {
                messageRepo.deleteOldMessages(days)
                "deleted messages older than $days day(s)"
            }
        }
        "mark_read" -> "marked ${messageRepo.markRead(args.longs("threads"))} read"
        "mark_unread" -> "marked ${messageRepo.markUnread(args.longs("threads"))} unread"
        "mark_archived" -> {
            val t = args.longs("threads")
            conversationRepo.markArchived(*t.toLongArray())
            "archived ${t.size}"
        }
        "mark_unarchived" -> {
            val t = args.longs("threads")
            conversationRepo.markUnarchived(t)
            "unarchived ${t.size}"
        }
        "mark_blocked" -> {
            val t = args.longs("threads")
            conversationRepo.markBlocked(t, prefs.blockingManager.get(), args.optNullString("reason"))
            "blocked ${t.size} (app-local)"
        }
        "mark_unblocked" -> {
            val t = args.longs("threads")
            conversationRepo.markUnblocked(*t.toLongArray())
            "unblocked ${t.size}"
        }
        "mark_pinned" -> {
            val t = args.longs("threads")
            conversationRepo.markPinned(*t.toLongArray())
            "pinned ${t.size}"
        }
        "mark_unpinned" -> {
            val t = args.longs("threads")
            conversationRepo.markUnpinned(*t.toLongArray())
            "unpinned ${t.size}"
        }
        "backfill" -> {
            if (args.optBoolean("reset", false)) BackfillWorker.reset(context)
            BackfillWorker.enqueue(context)
            "backfill started"
        }
        "fetch_attachment" -> {
            val sha = args.optString("sha")
            val mid = args.optString("message").substringAfterLast(':').toLongOrNull()
            val msg = if (mid != null) messageRepo.getMessage(mid) else null
            when {
                sha.isBlank() || msg == null -> "no such message"
                PartUploader.sendOne(context, msg, sha, base, token, client) -> "sent $sha"
                else -> "digest not found in that message"
            }
        }
        // Human -> a number, typed on the desktop. Same steps as the compose screen: get or
        // create the conversation in the app's database FIRST (a send alone leaves a brand-new
        // thread unregistered, and the list never shows it), then send on the default SIM,
        // then forward the sent copy back (dir=out) so the desktop archive shows the reply.
        // A bad number is not refused: it goes out, fails on the radio, and shows as a failed
        // message in its own thread, which is what a person expects to see. AGENTS replies
        // take the same route by way of MessageRepositoryImpl's intercept and never touch
        // the radio.
        "send" -> {
            val addr = args.optString("addr").trim()
            val body = args.optString("body")
            if (addr.isBlank() || body.isBlank()) "refused: addr and body are required" else {
                val conversation = conversationRepo.getOrCreateConversation(listOf(addr))
                if (conversation == null) "error: could not create a conversation for $addr" else {
                    val sent = messageRepo.sendNewMessages(
                        -1, conversation.recipients.map { it.address }, body, emptyList(), false)
                    val threads = sent.map { it.threadId }
                    conversationRepo.updateConversations(threads)
                    conversationRepo.markUnarchived(threads)
                    sent.forEach { ForwardMessageWorker.enqueue(context, it.id) }
                    JSONObject().put("messages", JSONArray(sent.map { "sms:${it.id}" }))
                }
            }
        }
        // Agent -> human: an inbox insert from AGENTS, then the normal receive pipeline for
        // the notification, but never the forwarder (see AgentChannel).
        "notify" -> {
            val body = args.optString("body")
            if (body.isBlank()) "refused: empty body" else {
                val msg = messageRepo.insertReceivedSms(-1, AgentChannel.ADDRESS, body, System.currentTimeMillis())
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<ReceiveSmsWorker>()
                        .setInputData(workDataOf(ReceiveSmsWorker.INPUT_DATA_KEY_MESSAGE_ID to msg.id))
                        .build())
                JSONObject().put("message", "sms:${msg.id}")
            }
        }
        "location" -> PhoneLocation.report(context)
        // What the phone decided about the live link, and from what. Diagnostics only.
        "live_status" -> LiveLink.status(context)
        else -> "unsupported op"
    }
}

// The desktop identifies messages as "sms:<rowid>" / "mms:<rowid>" -- the Realm primary key.
private fun JSONObject.longsFromIds(key: String): List<Long> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optString(it).substringAfterLast(':').toLongOrNull() }
}

private fun JSONObject.longs(key: String): List<Long> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull {
        when (val v = a.opt(it)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }
}

private fun JSONObject.strings(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
}

private fun JSONObject.optNullString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
