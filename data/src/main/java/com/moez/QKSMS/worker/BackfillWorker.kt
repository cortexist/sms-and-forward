/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Uploads the phone's existing message history to the sms-bridge, so the desktop
 * has more than a live tail.
 *
 * The forwarder only ever sends what arrives after it was switched on. On this
 * device that left the desktop holding three messages against nearly ten thousand
 * here, which makes every older conversation unreachable from the desktop and makes
 * any "oldest N" reasoning over there wrong by construction.
 *
 * SHAPE. Conversation at a time, newest conversation first, so the threads someone
 * actually wants show up early rather than after a full historical sweep. Each
 * conversation is uploaded in batches to /messages/bulk -- one request per message
 * would be ten thousand round trips, each one a chance to be interrupted.
 *
 * RESUMABLE, because it will be interrupted. The box hard-resets every few hours,
 * WorkManager stops workers that overrun, and the phone sleeps. Completed thread ids
 * are recorded as they finish, so a resumed run skips them; a conversation cut off
 * mid-upload simply repeats, and the server dedups on the stable message id. Nothing
 * is tracked per message, which keeps the cursor small and the failure mode boring.
 *
 * TIME-BOXED. Each run works for a fixed budget and then reschedules itself rather
 * than holding a worker open for however long ten thousand messages take.
 */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.bridge.BridgeConfig
import dev.octoshrimpy.quik.bridge.PartUploader
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
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

class BackfillWorker(appContext: Context, params: WorkerParameters)
    : Worker(appContext, params) {

    companion object {
        const val UNIQUE_NAME = "sms-bridge-backfill"
        private const val PREFS = "sms_bridge_backfill"
        private const val KEY_DONE = "completed_threads"

        const val BATCH = 250                    // messages per request
        private const val BUDGET_MS = 90_000L    // work for this long, then reschedule

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)   // a batch is much larger than a forward
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,     // a run already in flight continues; do not restart it
                OneTimeWorkRequestBuilder<BackfillWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }

        /** Forget the cursor, so the next run starts from scratch. */
        fun reset(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_DONE).apply()
        }
    }

    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var conversationRepo: ConversationRepository

    private val prefs by lazy {
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun doWork(): Result {
        val config = BridgeConfig.load(applicationContext)
        if (!config.usable) return Result.success()

        val base = config.endpoint.removeSuffix("/sms").trimEnd('/')
        val url = "$base/messages/bulk"
        // Attachments make a thread take far longer than text alone, so the budget
        // buys fewer conversations per run; the worker simply reschedules more often.

        val done = prefs.getStringSet(KEY_DONE, emptySet())!!.toMutableSet()
        // Snapshot, not a live query: this loop can run for a minute and a half, and a
        // live RealmResults reordering underneath it would be a nasty way to skip a
        // conversation.
        val threads = conversationRepo.getConversationsSnapshot(false).map { it.id }
        val remaining = threads.filter { it.toString() !in done }

        if (remaining.isEmpty()) {
            Timber.v("sms-bridge: backfill complete, ${threads.size} conversation(s)")
            return Result.success()
        }

        val deadline = System.currentTimeMillis() + BUDGET_MS
        var uploaded = 0

        for (threadId in remaining) {
            if (System.currentTimeMillis() > deadline) break

            val messages = try {
                messageRepo.getMessagesSync(threadId).map { m ->
                    // Digests and sizes only -- no bytes. Pulling every historic image
                    // is roughly 3 GB here and almost none of it gets looked at; the
                    // desktop asks for the ones it wants through the command queue.
                    val parts = PartUploader.upload(
                        applicationContext, m, base, config.token, client,
                        sendBytes = false)
                    encode(m, parts)
                }
            } catch (e: IOException) {
                // A network failure must NOT mark the thread complete. This handler was
                // written for Realm read errors and swallowed upload failures too, so a
                // dropped connection permanently skipped the conversation.
                prefs.edit().putStringSet(KEY_DONE, done).apply()
                Timber.v("sms-bridge: backfill paused on thread $threadId (network)")
                return if (runAttemptCount >= 10) Result.failure() else Result.retry()
            } catch (e: Exception) {
                Timber.w(e, "sms-bridge: could not read thread $threadId")
                done.add(threadId.toString())   // do not wedge the sweep on one bad thread
                continue
            }

            var ok = true
            for (chunk in messages.chunked(BATCH)) {
                try {
                    post(url, config.token, chunk)
                    uploaded += chunk.size
                } catch (e: IOException) {
                    ok = false
                    break
                }
            }

            if (!ok) {
                // Persist what finished before giving up, so the retry does not redo it.
                prefs.edit().putStringSet(KEY_DONE, done).apply()
                Timber.v("sms-bridge: backfill interrupted after $uploaded message(s)")
                return if (runAttemptCount >= 10) Result.failure() else Result.retry()
            }

            done.add(threadId.toString())
            prefs.edit().putStringSet(KEY_DONE, done).apply()
        }

        val left = threads.count { it.toString() !in done }
        Timber.v("sms-bridge: backfill uploaded $uploaded, $left conversation(s) left")

        // More to do: come back rather than overrun this worker's welcome.
        if (left > 0) enqueue(applicationContext)
        return Result.success()
    }

    private fun encode(m: Message, parts: JSONArray = JSONArray()): JSONObject {
        val kind = if (m.type == Message.TYPE_MMS) "mms" else "sms"
        val body = m.body.ifBlank {
            m.parts.filter { it.type.startsWith("text/") }.mapNotNull { it.text }
                .joinToString("\n")
        }
        return JSONObject().apply {
            put("id", "$kind:${m.id}")
            // boxId tells inbox from sent; without it every historic conversation would
            // read as if only the other side ever spoke.
            put("dir", if (m.isMe()) "out" else "in")
            put("ts", m.date / 1000)
            put("addr", m.address)
            put("body", body)
            put("kind", kind)
            put("sub", m.subId)
            put("thread", m.threadId)
            if (parts.length() > 0) put("parts", parts)
        }
    }

    private fun post(url: String, token: String, batch: List<JSONObject>) {
        val payload = JSONObject()
            .put("messages", JSONArray().also { arr -> batch.forEach { arr.put(it) } })
            .toString()
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("bulk upload failed: http ${r.code}")
        }
    }
}
