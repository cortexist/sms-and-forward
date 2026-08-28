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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.octoshrimpy.quik.model.Message
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
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

    const val THUMB_PX = 160        // sized for a half-block render, not for viewing
    const val THUMB_QUALITY = 72

    /**
     * A small JPEG of an image part, or null.
     *
     * inSampleSize decodes every Nth pixel straight out of the JPEG rather than
     * decoding it fully and shrinking afterwards, so the full-size bitmap is never
     * materialised. Measured on this archive: ~16 ms and ~4.7 KB per image, against
     * a 2.1 MB original -- 454x smaller, and about 8 MB for a ten-year history.
     * The desktop is a management tool, so a thumbnail is what it almost always
     * wants; the original stays on the phone until something asks for it.
     */
    private fun thumbnail(context: Context, uri: android.net.Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMB_PX &&
                   bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            val scale = minOf(THUMB_PX.toFloat() / decoded.width,
                              THUMB_PX.toFloat() / decoded.height, 1f)
            val bmp = if (scale < 1f)
                Bitmap.createScaledBitmap(decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1), true)
            else decoded

            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
                out.toByteArray()
            }
        } catch (e: Exception) {
            Timber.w("sms-bridge: thumbnail failed (${e.javaClass.simpleName})")
            null
        }
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
            meta.put("sha", sha)

            // Thumbnail always: it is what the desktop renders, and the whole archive
            // of them is a few megabytes. The original goes only when asked for.
            thumbnail(context, part.getUri())?.let { thumb ->
                val tsha = MessageDigest.getInstance("SHA-256").digest(thumb)
                    .joinToString("") { "%02x".format(it) }
                if (!held(base, token, tsha, client))
                    put(base, token, tsha, "image/jpeg", thumb, client)
                meta.put("thumb", tsha).put("thumb_size", thumb.size)
            }

            if (sendBytes && !held(base, token, sha, client)) {
                put(base, token, sha, type, bytes, client)
            }
            out.put(meta)
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
