/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * The live link: a foreground service that long-polls the bridge's command
 * queue while LiveLink says the human is at the desk. Same commands, same
 * applier, same acks as CommandWorker -- only the latency differs: a reply
 * typed on the desktop leaves the phone within a second instead of within the
 * next poll. A foreground service is the only thing Android lets hold a socket
 * open with the screen off, hence the (silent, minimal) notification while it
 * runs. CommandWorker stands down while this is up so a command is never
 * applied twice.
 */
package dev.octoshrimpy.quik.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.bridge.BridgeConfig
import dev.octoshrimpy.quik.bridge.CommandApplier
import dev.octoshrimpy.quik.bridge.LiveLink
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
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

class LiveBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "sms_bridge_live"
        private const val NOTIFICATION_ID = 0x5B1D
        private const val WAIT_S = 25                          // server-side hold per long-poll
        private const val MAX_FAILURES = 3                     // then back to the queue
        private const val MAX_SESSION_MS = 4 * 60 * 60 * 1000L // a hard cap, whatever the box says
        private val JSON = "application/json; charset=utf-8".toMediaType()

        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            if (running) return
            ContextCompat.startForegroundService(context, Intent(context, LiveBridgeService::class.java))
        }

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout((WAIT_S + 15).toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var prefs: Preferences

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            AndroidInjection.inject(this)
        } catch (e: Exception) {
            LiveLink.note(this, "service_error", "inject: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (e: Exception) {
            LiveLink.note(this, "service_error", "startForeground: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        LiveLink.note(this, "service_error", null)
        if (worker == null) {
            running = true
            worker = Thread(::loop, "sms-bridge-live").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun loop() {
        val started = System.currentTimeMillis()
        var failures = 0
        try {
            val config = BridgeConfig.load(this)
            if (!config.usable) return
            val base = config.endpoint.removeSuffix("/sms").trimEnd('/')
            val applier = CommandApplier(applicationContext, messageRepo, conversationRepo, prefs)
            Timber.v("sms-bridge: live link up")
            while (running && System.currentTimeMillis() - started < MAX_SESSION_MS) {
                val response = try {
                    get("$base/commands?wait=$WAIT_S", config.token)
                } catch (e: IOException) {
                    if (++failures >= MAX_FAILURES) { Timber.v("sms-bridge: live link lost (${e.javaClass.simpleName})"); return }
                    Thread.sleep(5_000); continue
                }
                failures = 0
                if (!LiveLink.shouldRun(this, response.optJSONObject("live"))) { Timber.v("sms-bridge: live link no longer wanted"); return }
                val commands = response.optJSONArray("commands") ?: JSONArray()
                if (commands.length() == 0) continue
                val acked = JSONArray(); val results = JSONObject()
                for (i in 0 until commands.length()) {
                    val cmd = commands.optJSONObject(i) ?: continue
                    val id = cmd.optString("id"); if (id.isBlank()) continue
                    val op = cmd.optString("op")
                    val outcome = try {
                        applier.apply(op, cmd.optJSONObject("args") ?: JSONObject(), base, config.token, client)
                    } catch (e: Throwable) {
                        Timber.w(e, "sms-bridge: live command $op failed"); "error: ${e.javaClass.simpleName}"
                    }
                    acked.put(id); results.put(id, outcome)
                    Timber.v("sms-bridge: live $op -> ${if (outcome is JSONObject) "(object)" else outcome}")
                }
                try {
                    ack("$base/commands/ack", config.token, acked, results)
                } catch (e: IOException) {
                    Timber.w("sms-bridge: live ack failed (${e.javaClass.simpleName}); the queue drain will re-ack")
                }
            }
        } catch (e: InterruptedException) {
            // asked to stop
        } finally {
            running = false
            worker = null
            Timber.v("sms-bridge: live link down")
            stopSelf()
        }
    }

    private fun get(url: String, token: String): JSONObject {
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("http ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    private fun ack(url: String, token: String, ids: JSONArray, results: JSONObject) {
        val payload = JSONObject().put("ids", ids).put("results", results).toString()
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r -> if (!r.isSuccessful) throw IOException("ack failed: http ${r.code}") }
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Bridge live link", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) })
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live link to the desktop")
            .setContentText("Replies typed on the computer leave at once")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
