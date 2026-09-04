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
 * EVERY ATTEMPTED COMMAND IS ACKED, INCLUDING FAILURES, with a result the desktop
 * displays -- a string, or a JSON object for the commands that are questions
 * (`location`). Not acking a permanently-broken command (unknown op, malformed
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
import dev.octoshrimpy.quik.bridge.CommandApplier
import dev.octoshrimpy.quik.bridge.LiveLink
import dev.octoshrimpy.quik.service.LiveBridgeService
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.Conversation
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

        // Commands applied per run. An attachment fetch is a megabyte or two off the
        // phone, so a queue of two dozen outlives the worker -- and because the ack
        // only happens after the whole batch, everything done so far was thrown away
        // on the first failure. Small batches commit progress instead.
        const val PER_RUN = 6

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Drain now. Unique + KEEP: several forwards arriving together must not
         *  stack up several drains of the same queue. */
        fun enqueue(context: Context) {
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        /** Drain now, REPLACING any recorded run. For app start only: the reinstall
         *  wedge described on schedulePeriodic() hits this unique name too -- the
         *  platform job is cancelled but the record stays ENQUEUED, and every KEEP
         *  thereafter is a silent no-op. Observed 2026-08-31: forwards flowing for
         *  over an hour, each one calling enqueue(), zero drains. REPLACE at process
         *  start clears the wedged record; the KEEP path stays correct between. */
        fun enqueueFresh(context: Context) {
            enqueue(context, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                policy,
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

    // Set for the lifetime of a doWork() call so the dispatch table can reach them.
    private var endpointBase: String = ""
    private var authToken: String = ""

    private fun baseOf() = endpointBase
    private fun tokenOf() = authToken

    override fun doWork(): Result {
        val config = BridgeConfig.load(applicationContext)
        // Logged even when inert: a queue that never drains must be visible in the log.
        Timber.v("sms-bridge: command drain (usable=${config.usable}, attempt=$runAttemptCount)")
        if (!config.usable) return Result.success()

        val base = config.endpoint.removeSuffix("/sms").trimEnd('/')
        endpointBase = base
        authToken = config.token

        // While the live link holds the queue, this drain would apply the same commands twice.
        if (LiveBridgeService.running) return Result.success()

        // Report what is blocked here before draining. QUIK's block is app-local and
        // reversible -- a quarantine in all but name -- so marking junk on the phone
        // needs no new UI, it just has to reach the desktop. Best effort: a failure
        // here must not stop commands being applied.
        try {
            pushBlocked("$base/blocked", config.token)
        } catch (e: Exception) {
            Timber.v("sms-bridge: block list not pushed (${e.javaClass.simpleName})")
        }

        val fetched = try {
            fetch("$base/commands", config.token)
        } catch (e: IOException) {
            // Expected while the box is resetting or the phone is off-tailnet.
            return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        } ?: return Result.failure()

        val commands = fetched.optJSONArray("commands") ?: JSONArray()
        if (commands.length() == 0) {
            startLiveLinkIfWanted(fetched.optJSONObject("live"))
            return Result.success()
        }

        val take = minOf(commands.length(), PER_RUN)
        val more = commands.length() > take

        val acked = JSONArray()
        val results = JSONObject()
        for (i in 0 until take) {
            val cmd = commands.optJSONObject(i) ?: continue
            val id = cmd.optString("id")
            if (id.isBlank()) continue
            val op = cmd.optString("op")
            val outcome = try {
                apply(op, cmd.optJSONObject("args") ?: JSONObject())
            } catch (e: Throwable) {
                // Throwable, not Exception: a NoClassDefFoundError or VerifyError from one
                // command must be acked as an error, not kill the process with the batch unacked.
                Timber.w(e, "sms-bridge: command $op failed")
                "error: ${e.javaClass.simpleName}: ${e.message}"
            }
            acked.put(id)
            results.put(id, outcome)
            // A location answer is not for the log.
            Timber.v("sms-bridge: $op -> ${if (outcome is JSONObject) "(object)" else outcome}")
        }

        return try {
            ack("$base/commands/ack", config.token, acked, results)
            // Come straight back for the rest rather than waiting for the next poll.
            if (more) enqueue(applicationContext)
            startLiveLinkIfWanted(fetched.optJSONObject("live"))
            Result.success()
        } catch (e: IOException) {
            // The work was done but the ack did not land. Retrying is safe: applying
            // twice is a no-op (a deleted message stays deleted) and the server
            // treats a repeated ack as a no-op too.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /** After the batch is applied and acked -- never before, so a failing service cannot
     *  leave commands stuck -- hand the queue to the live link if the box wants one and
     *  we are on its LAN. Android 12+ may refuse a foreground service started from the
     *  background; that is recorded for live_status and the queue keeps polling. */
    private fun startLiveLinkIfWanted(live: JSONObject?) {
        val wanted = try {
            LiveLink.shouldRun(applicationContext, live)
        } catch (e: Throwable) {
            Timber.w(e, "sms-bridge: live link decision failed (${e.javaClass.simpleName})")
            return
        }
        if (!wanted) return
        try {
            LiveBridgeService.start(applicationContext)
            LiveLink.note(applicationContext, "error", null)
            Timber.v("sms-bridge: live link requested")
        } catch (e: Throwable) {
            LiveLink.note(applicationContext, "error", "${e.javaClass.simpleName}: ${e.message}")
            Timber.w(e, "sms-bridge: live link could not start (${e.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------------ apply

    private val applier by lazy { CommandApplier(applicationContext, messageRepo, conversationRepo, prefs) }

    private fun apply(op: String, args: JSONObject): Any = applier.apply(op, args, baseOf(), tokenOf(), client)

    // ------------------------------------------------------------------- http

    /** Upload the app-local block state so a phone-side verdict reaches the desktop.
     *
     *  Two sources, because "Block" on a conversation only sets Conversation.blocked;
     *  the BlockedNumber list is populated separately, and only when the QKSMS
     *  blocking client is the active one. Reading BlockedNumber alone missed every
     *  conversation the operator blocked from the list screen. Blocked conversations
     *  carry their thread id, which is the identity the desktop keys verdicts on.
     *
     *  Always posted, even when empty: the desktop treats the payload as the complete
     *  current state, so an unblock on the phone is a release on the desktop.
     *
     *  Queried synchronously here rather than through BlockingRepository, whose
     *  getBlockedNumbers() uses findAllAsync(): an async Realm query needs a Looper
     *  thread and doWork() has none, so it threw IllegalStateException. isBlocked()
     *  in the same repository already uses the synchronous form for this reason.
     */
    private fun pushBlocked(url: String, token: String) {
        val addrs = JSONArray()
        val blocked = JSONArray()
        val pinned = JSONArray()
        val seen = HashSet<String>()
        Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java).findAll()
                .forEach { if (it.address.isNotBlank() && seen.add(it.address)) addrs.put(it.address) }
            realm.where(Conversation::class.java).equalTo("blocked", true).findAll()
                .forEach { c ->
                    val convAddrs = JSONArray()
                    c.recipients.forEach { r ->
                        if (r.address.isNotBlank()) {
                            convAddrs.put(r.address)
                            if (seen.add(r.address)) addrs.put(r.address)
                        }
                    }
                    blocked.put(JSONObject().put("thread", c.id).put("addrs", convAddrs))
                }
            // Pins ride the same push, same complete-state contract as "blocked":
            // the box mirrors this list and shows those conversations first.
            realm.where(Conversation::class.java).equalTo("pinned", true).findAll()
                .forEach { c ->
                    val convAddrs = JSONArray()
                    c.recipients.forEach { r ->
                        if (r.address.isNotBlank()) convAddrs.put(r.address)
                    }
                    pinned.put(JSONObject().put("thread", c.id).put("addrs", convAddrs))
                }
        }
        val body = JSONObject().put("addrs", addrs).put("blocked", blocked)
            .put("pinned", pinned).toString()
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("blocked push failed: http ${r.code}")
        }
    }

    private fun fetch(url: String, token: String): JSONObject? {
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                Timber.w("sms-bridge: GET commands -> http ${r.code}")
                return null
            }
            return JSONObject(r.body?.string().orEmpty())
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

