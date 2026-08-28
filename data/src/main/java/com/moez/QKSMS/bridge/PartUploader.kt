/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * Uploads MMS parts to the sms-bridge, shared by the live forwarder and the backfill.
 *
 * Content-addressed by SHA-256, so the same picture sent to two people is stored once
 * and a repeated upload cannot duplicate it. The digest is also the integrity check --
 * the server recomputes it and rejects a mismatch rather than trusting our label.
 *
 * PROBE BEFORE SENDING. Backfill resumes per conversation, so an interrupted run
 * repeats a thread; without the HEAD it would re-send every image in it. With it,
 * resuming costs a round trip per part instead of the bytes. That matters at ~1,800
 * images.
 *
 * Images only by default. Video is described rather than transferred: a hundred clips
 * would dwarf everything else on the tailnet for something nobody is going to watch
 * in a terminal.
 */
package dev.octoshrimpy.quik.bridge

import android.content.Context
import dev.octoshrimpy.quik.model.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.security.MessageDigest

object PartUploader {

    const val MAX_ATTACHMENT = 4 * 1024 * 1024

    /** Send one specific part, identified by digest. Used to satisfy a desktop
     *  request for an image it has chosen to look at. */
    fun sendOne(context: Context, message: Message, sha: String, base: String,
                token: String, client: OkHttpClient): Boolean {
        for (part in message.parts) {
            if (skipped(part.type)) continue
            val bytes = try {
                context.contentResolver.openInputStream(part.getUri())?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            } ?: continue
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (digest == sha) {
                // Probe first: a fetch can name something already held from an earlier
                // run, and without this the bytes crossed the tailnet to be discarded.
                if (!held(base, token, sha, client))
                    put(base, token, sha, part.type, bytes, client)
                return true
            }
        }
        return false
    }

    /** Types worth carrying across the tailnet and drawable at the far end. */
    private val TRANSFERABLE = listOf("image/")

    private fun skipped(type: String) =
        type.startsWith("text/") || type == "application/smil"

    /**
     * @param sendBytes false to record the digest and size WITHOUT transferring.
     *
     * Backfill uses false. A full history here is ~1,800 images and about 3 GB, and
     * almost none of it is ever looked at -- the cost is real and the value is not.
     * The digest is still computed (a local read), so the desktop can ask for any
     * single image later through the command queue and get it in seconds.
     */
    fun upload(context: Context, message: Message, base: String, token: String,
               client: OkHttpClient, sendBytes: Boolean = true): JSONArray {
        val out = JSONArray()
        for (part in message.parts) {
            val type = part.type
            if (skipped(type)) continue

            val meta = JSONObject().put("mime", type).put("name", part.name ?: "")

            if (TRANSFERABLE.none { type.startsWith(it) }) {
                out.put(meta.put("skipped", "not-media"))
                continue
            }

            val bytes = try {
                context.contentResolver.openInputStream(part.getUri())?.use { it.readBytes() }
            } catch (e: Exception) {
                Timber.w("sms-bridge: part ${part.id} unreadable")
                null
            }

            if (bytes == null) {
                out.put(meta.put("skipped", "unreadable"))
                continue
            }
            meta.put("size", bytes.size)
            if (bytes.size > MAX_ATTACHMENT) {
                out.put(meta.put("skipped", "too-large"))
                continue
            }

            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (sendBytes && !held(base, token, sha, client)) {
                put(base, token, sha, type, bytes, client)
            }
            out.put(meta.put("sha", sha))
        }
        return out
    }

    private fun held(base: String, token: String, sha: String, client: OkHttpClient): Boolean =
        try {
            val req = Request.Builder().url("$base/attachments/$sha")
                .addHeader("Authorization", "Bearer $token").head().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false          // probe failure just means we try the upload
        }

    private fun put(base: String, token: String, sha: String, mime: String,
                    bytes: ByteArray, client: OkHttpClient) {
        val req = Request.Builder().url("$base/attachments/$sha")
            .addHeader("Authorization", "Bearer $token")
            .post(bytes.toRequestBody(mime.toMediaType())).build()
        client.newCall(req).execute().use { r ->
            // 409 is "already held" -- with content addressing that is success.
            if (!r.isSuccessful && r.code != 409)
                throw IOException("attachment upload failed: http ${r.code}")
        }
    }
}
