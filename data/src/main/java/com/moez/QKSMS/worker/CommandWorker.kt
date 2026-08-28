/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Drains the sms-bridge command queue: fetch what the desktop asked for, apply it,
 * acknowledge it.
 *
 * WHY THE PHONE POLLS INSTEAD OF BEING PUSHED TO. An inbound socket here would need
 * a foreground service to survive Doze - a permanent notification and continuous
 * battery cost - to save latency on operations (delete, block, archive) where nobody
 * is waiting on the result. So the desktop queues and the phone collects. Two
 * triggers: a periodic worker for the quiet case, and ForwardMessageWorker enqueueing
 * this one whenever a forward response carries commands, which means the common case
 * costs no extra round trip at all.
 *
 * THE COMMANDS ARE QUIK'S OWN VERBS. The vocabulary is deliberately the interactor
 * set, so a button in the desktop UI does exactly what the identically-labelled
 * button here does - including that "block" is app-local, because QUIK's block is a
 * per-install Realm list. To make a message actually go away, the command is delete,
 * which goes through to the system provider.
 *
 * EVERY ATTEMPTED COMMAND IS ACKED, INCLUDING FAILURES, with a result string the
 * desktop displays. Not acking a permanently-broken command (unknown op, malformed
 * args) would retry it forever; acking with an error is honest and lets the operator
 * see it and reissue. Transient failures are therefore surfaced rather than silently
 * retried - a deliberate trade against an unbounded retry loop.
 */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.bridge.BridgeConfig
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import io.realm.Realm
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CommandWorker(appContext: Context, params: WorkerParameters)
    : Worker(appContext, params) {

    companion object {
        const val UNIQUE_NAME = "sms-bridge-commands"
        const val MAX_ATTEMPTS = 12

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Drain now. Unique + KEEP: several forwards arriving together must not
         *  stack up several drains of the same queue. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CommandWorker>()
                    .setConstraints(net())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }

        /** The quiet-case trigger. 15 minutes is WorkManager's floor for periodic work,
         *  and it is the ceiling on how stale a desktop action can look -- acceptable for
         *  delete/block/archive, which is why sending is not routed through here.
         *
         *  UPDATE, not KEEP, and this matters: reinstalling the app makes the platform
         *  cancel its jobs while WorkManager's own database still records the work as
         *  enqueued, so KEEP sees "already there", does nothing, and the worker silently
         *  never runs again. Observed exactly that on 2026-08-27. HousekeepingWorker
         *  already uses UPDATE for the same reason. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "$UNIQUE_NAME-periodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CommandWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(net())
                    .build()
            )
        }

        private fun net() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var prefs: Preferences

    override fun doWork(): Result {
        val config = BridgeConfig.load(applicationContext)
        if (!config.usable) return Result.success()

        val base = config.endpoint.removeSuffix("/sms").trimEnd('/')

        // Report what is blocked here before draining. QUIK's block is app-local and
        // reversible -- a quarantine in all but name -- so marking junk on the phone
        // needs no new UI, it just has to reach the desktop. Best effort: a failure
        // here must not stop commands being applied.
        try {
            pushBlocked("$base/blocked", config.token)
        } catch (e: Exception) {
            Timber.v("sms-bridge: block list not pushed (${e.javaClass.simpleName})")
        }

        val commands = try {
            fetch("$base/commands", config.token)
        } catch (e: IOException) {
            // Expected while the box is resetting or the phone is off-tailnet.
            return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        } ?: return Result.failure()

        if (commands.length() == 0) return Result.success()

        val acked = JSONArray()
        val results = JSONObject()
        for (i in 0 until commands.length()) {
            val cmd = commands.optJSONObject(i) ?: continue
            val id = cmd.optString("id")
            if (id.isBlank()) continue
            val op = cmd.optString("op")
            val outcome = try {
                apply(op, cmd.optJSONObject("args") ?: JSONObject())
            } catch (e: Exception) {
                Timber.w(e, "sms-bridge: command $op failed")
                "error: ${e.javaClass.simpleName}"
            }
            acked.put(id)
            results.put(id, outcome)
            Timber.v("sms-bridge: $op -> $outcome")
        }

        return try {
            ack("$base/commands/ack", config.token, acked, results)
            Result.success()
        } catch (e: IOException) {
            // The work was done but the ack did not land. Retrying is safe: applying
            // twice is a no-op (a deleted message stays deleted) and the server
            // treats a repeated ack as a no-op too.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    // ------------------------------------------------------------------ apply

    private fun apply(op: String, args: JSONObject): String = when (op) {
        "delete_messages" -> {
            val ids = args.longsFromIds("ids")
            messageRepo.deleteMessages(ids)
            "deleted ${ids.size}"
        }
        "delete_conversations" -> {
            // Addressable either way. getConversation (not getOrCreate) on purpose:
            // deleting a conversation must never create one as a side effect.
            val byId = args.longs("threads")
            val byAddr = args.strings("addrs").mapNotNull {
                conversationRepo.getConversation(listOf(it))?.id
            }
            val t = (byId + byAddr).distinct()
            if (t.isEmpty()) "no matching conversation" else {
                conversationRepo.deleteConversations(*t.toLongArray())
                "deleted ${t.size} conversation(s)"
            }
        }
        // FIFO trim. Evaluated here because only the phone holds the complete chain;
        // the desktop's archive starts wherever forwarding started, so it cannot know
        // which messages are genuinely the oldest.
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
        // Asymmetric on purpose: markUnarchived takes a Collection, markArchived a vararg.
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
        // Sending needs a subscription, a thread and the send pipeline; it is also the
        // one verb where poll latency is unacceptable, so it is not wired to this path.
        // Kicked off here rather than run inline: a full sweep takes many minutes and
        // several worker lifetimes, so the command only starts it and returns.
        "backfill" -> {
            if (args.optBoolean("reset", false)) BackfillWorker.reset(applicationContext)
            BackfillWorker.enqueue(applicationContext)
            "backfill started"
        }
        "send" -> "unsupported: sending is not implemented over the command queue"
        else -> "unsupported op"
    }

    // ------------------------------------------------------------------- http

    /** Upload the app-local block list so a phone-side verdict reaches the desktop.
     *
     *  Queried synchronously here rather than through BlockingRepository, whose
     *  getBlockedNumbers() uses findAllAsync(): an async Realm query needs a Looper
     *  thread and doWork() has none, so it threw IllegalStateException. isBlocked()
     *  in the same repository already uses the synchronous form for this reason.
     */
    private fun pushBlocked(url: String, token: String) {
        val addrs = JSONArray()
        Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java).findAll()
                .forEach { if (it.address.isNotBlank()) addrs.put(it.address) }
        }
        if (addrs.length() == 0) return
        val body = JSONObject().put("addrs", addrs).toString()
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("blocked push failed: http ${r.code}")
        }
    }

    private fun fetch(url: String, token: String): JSONArray? {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                Timber.w("sms-bridge: GET commands -> http ${r.code}")
                return null
            }
            val body = r.body?.string().orEmpty()
            return JSONObject(body).optJSONArray("commands") ?: JSONArray()
        }
    }

    private fun ack(url: String, token: String, ids: JSONArray, results: JSONObject) {
        val payload = JSONObject().put("ids", ids).put("results", results).toString()
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("ack failed: http ${r.code}")
        }
    }
}

// The desktop identifies messages as "sms:<rowid>" / "mms:<rowid>" -- the same stable
// id the forwarder sends, which is the Realm primary key, so it round-trips exactly.
private fun JSONObject.longsFromIds(key: String): List<Long> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull {
        a.optString(it).substringAfterLast(':').toLongOrNull()
    }
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
    return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
}

private fun JSONObject.optNullString(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
