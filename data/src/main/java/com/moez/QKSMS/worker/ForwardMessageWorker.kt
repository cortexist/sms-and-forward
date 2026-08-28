/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Forwards a received message to the sms-bridge endpoint on the user's box.
 *
 * EVERY message is forwarded, unconditionally. Filtering here was considered and rejected: it
 * would require the phone to know which agent on the far side is waiting for what, and an
 * unknown sender would be silently missed. Forwarding everything makes this side stateless and
 * moves the "am I waiting for a code" question to the box, where the agent already lives.
 * See docs/sms-bridge.md.
 *
 * Retry policy matters more than usual here: the host this talks to may be unreachable for long
 * stretches, so "endpoint unreachable" is a routine condition, not an error. WorkManager persists
 * the queue across app death AND device reboot, so a message received while the box is down is
 * delivered when it comes back rather than lost.
 */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.octoshrimpy.quik.bridge.BridgeConfig
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.MessageRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ForwardMessageWorker(appContext: Context, params: WorkerParameters)
    : Worker(appContext, params) {

    companion object {
        const val INPUT_DATA_KEY_MESSAGE_ID = "messageId"
        const val MAX_ATTACHMENT = 4 * 1024 * 1024   // an MMS part larger than this is not worth the tailnet

        fun enqueue(context: Context, messageId: Long) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ForwardMessageWorker>()
                    .setInputData(workDataOf(INPUT_DATA_KEY_MESSAGE_ID to messageId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }
        const val MAX_ATTEMPTS = 24        // with exponential backoff, ~ a day of retrying

        private val JSON = "application/json; charset=utf-8".toMediaType()

        // Short timeouts: the peer is one WireGuard hop away, and a worker holding a socket
        // open for a minute is a worker not being retried.
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

    override fun doWork(): Result {
        val config = BridgeConfig.load(applicationContext)
        if (!config.usable) return Result.success()   // inert unless deliberately configured

        val messageId = inputData.getLong(INPUT_DATA_KEY_MESSAGE_ID, -1)
        if (messageId < 0) return Result.failure()

        // A message deleted before we got to it is not a failure worth retrying.
        val message = messageRepo.getMessage(messageId) ?: return Result.success()

        // Attachments first: the message record references them by digest, so posting
        // the message before its parts would leave the desktop with a dangling id.
        val attachments = try {
            uploadParts(message, base(config.endpoint), config.token)
        } catch (e: IOException) {
            return retryOrGiveUp("attachment: ${e.javaClass.simpleName}")
        }

        val payload = try {
            encode(message, attachments)
        } catch (e: Exception) {
            Timber.e(e, "sms-bridge: could not encode message")
            return Result.failure()
        }

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.token}")
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        // Deliberately not logging the body or any extracted code.
                        Timber.v("sms-bridge: forwarded message $messageId")
                        // The response piggybacks whatever the desktop queued. Rather
                        // than applying it here (which would drag every repository into
                        // this worker), hand off to CommandWorker, which is the single
                        // place commands are applied.
                        if (hasCommands(response.peekBody(65536).string())) {
                            CommandWorker.enqueue(applicationContext)
                        }
                        Result.success()
                    }
                    // 401/413/400 will not improve by being sent again.
                    response.code in 400..499 -> {
                        Timber.w("sms-bridge: endpoint rejected message, http ${response.code}")
                        Result.failure()
                    }
                    else -> retryOrGiveUp("http ${response.code}")
                }
            }
        } catch (e: IOException) {
            // The expected case while the box is resetting.
            retryOrGiveUp(e.javaClass.simpleName)
        }
    }

    /** True when the forward response carried queued commands. Never throws: a
     *  malformed body must not turn a successful forward into a failure. */
    private fun hasCommands(body: String): Boolean = try {
        (JSONObject(body).optJSONArray("commands")?.length() ?: 0) > 0
    } catch (e: Exception) {
        false
    }

    private fun retryOrGiveUp(reason: String): Result =
        if (runAttemptCount >= MAX_ATTEMPTS) {
            Timber.w("sms-bridge: giving up after $runAttemptCount attempts ($reason)")
            Result.failure()
        } else {
            Timber.v("sms-bridge: retry $runAttemptCount ($reason)")
            Result.retry()
        }

    /**
     * The v1 record shape. `id` must be stable across retries or every retry duplicates on the
     * far side - the Realm primary key gives us that for free.
     */
    private fun base(endpoint: String) = endpoint.removeSuffix("/sms").trimEnd('/')

    /** Upload image/video parts, returning the metadata to reference them by.
     *
     *  Content-addressed by SHA-256: the same picture forwarded twice is stored once,
     *  and a retry cannot duplicate it. Non-media and oversized parts are described
     *  but not uploaded -- the desktop shows that something was attached without the
     *  tailnet carrying a 30 MB video.
     */
    private fun uploadParts(message: Message, base: String, token: String): JSONArray {
        val out = JSONArray()
        for (part in message.parts) {
            val type = part.type
            if (type.startsWith("text/") || type == "application/smil") continue

            val bytes = try {
                applicationContext.contentResolver.openInputStream(part.getUri())
                    ?.use { it.readBytes() }
            } catch (e: Exception) {
                Timber.w("sms-bridge: part ${part.id} unreadable")
                null
            }

            val meta = JSONObject()
                .put("mime", type)
                .put("name", part.name ?: "")
                .put("size", bytes?.size ?: 0)
            if (bytes != null && bytes.size in 1..MAX_ATTACHMENT) {
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                putAttachment(base, token, sha, type, bytes)
                meta.put("sha", sha)
            } else {
                meta.put("skipped", if (bytes == null) "unreadable" else "too-large")
            }
            out.put(meta)
        }
        return out
    }

    private fun putAttachment(base: String, token: String, sha: String,
                              mime: String, bytes: ByteArray) {
        val req = Request.Builder().url("$base/attachments/$sha")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", mime)
            .post(bytes.toRequestBody(mime.toMediaType())).build()
        client.newCall(req).execute().use { r ->
            // 409 means the desktop already has it -- content addressing makes that
            // success, not an error.
            if (!r.isSuccessful && r.code != 409)
                throw IOException("attachment upload failed: http ${r.code}")
        }
    }

    private fun encode(message: Message, attachments: JSONArray = JSONArray()): String {
        val kind = if (message.type == Message.TYPE_MMS) "mms" else "sms"
        val body = message.body.ifBlank {
            // MMS keeps its text in parts rather than in body.
            message.parts
                .filter { it.type.startsWith("text/") }
                .mapNotNull { it.text }
                .joinToString("\n")
        }

        return JSONObject().apply {
            put("id", "$kind:${message.id}")
            put("dir", "in")
            put("ts", message.date / 1000)
            put("addr", message.address)
            put("body", body)
            put("kind", kind)
            put("sub", message.subId)
            // The desktop groups by address, but the phone's threadId is the precise
            // handle for a conversation; sending it lets a delete name the chain
            // exactly instead of relying on address matching.
            put("thread", message.threadId)
            if (attachments.length() > 0) put("parts", attachments)
        }.toString()
    }
}
