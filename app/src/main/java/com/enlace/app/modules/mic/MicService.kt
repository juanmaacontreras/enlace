package com.enlace.app.modules.mic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import com.enlace.app.MainActivity
import com.enlace.app.R
import com.enlace.app.Strings
import com.enlace.app.core.Link
import com.enlace.app.core.LinkException
import com.enlace.app.core.Messages
import com.enlace.app.core.Modules
import com.enlace.app.core.Protocol
import kotlin.concurrent.thread

/**
 * Captures the microphone and streams it to the PC while a notification is
 * showing. A foreground service because recording must survive the screen
 * turning off and the app leaving the screen.
 *
 * A dead microphone looks exactly like a working one, so nothing here fails
 * quietly: a dropped connection is retried for half a minute, and if it does
 * not come back the service stops and says so in a notification.
 */
class MicService : Service() {
    enum class State { OFF, CONNECTING, ON }

    companion object {
        private const val RATE = 48000
        private const val FRAME_BYTES = RATE / 50 * 2   // 20 ms of mono 16-bit
        private const val STALL_MS = 5000L
        private const val MAX_RETRIES = 15
        private const val RETRY_MS = 2000L
        private const val ONGOING = 1
        private const val LOST = 2
        private const val CHANNEL = "mic"
        private const val ACTION_STOP = "com.enlace.app.mic.STOP"

        @Volatile var state = State.OFF
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MicService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MicService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopping = false
    @Volatile private var link: Link? = null
    @Volatile private var lastWrite = 0L
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        foreground(Strings.MIC_CONNECTING)
        getSystemService(NotificationManager::class.java).cancel(LOST)
        setState(State.CONNECTING)
        holdLocks()
        worker = thread(name = "enlace-mic") { work() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        link?.close()
        releaseLocks()
        setState(State.OFF)
        super.onDestroy()
    }

    private fun shutDown() {
        stopping = true
        link?.close()          // unblocks a write stuck on a dead network
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- the stream --------------------------------------------------------

    private fun work() {
        var failures = 0
        var fatal: String? = null
        while (!stopping) {
            try {
                stream { failures = 0 }
            } catch (e: LinkException) {
                if (e.code != LinkException.UNREACHABLE) { fatal = e.code; break }
            } catch (e: Exception) {
                // Dropped: fall through and try again.
            }
            if (stopping) break
            if (++failures > MAX_RETRIES) { fatal = LinkException.UNREACHABLE; break }
            setState(State.CONNECTING)
            notifyOngoing(Strings.MIC_RECONNECTING)
            try { Thread.sleep(RETRY_MS) } catch (e: InterruptedException) { break }
        }
        if (fatal != null && !stopping) {
            main.post {
                notifyLost()
                shutDown()
            }
        }
    }

    /** One connection: open, announce, record until something breaks or we stop. */
    private fun stream(onStreaming: () -> Unit) {
        val opened = Link.open(this, Modules.names)
        link = opened
        var record: AudioRecord? = null
        val watchdog = thread(name = "enlace-mic-watchdog") { watch(opened) }
        try {
            if (MicModule.name !in opened.modules) throw LinkException("no_mic_module")
            val reply = opened.request(Messages.micStart(RATE, 1))
            if (reply.type != "started") throw LinkException(reply.header.optString("code", "refused"))
            opened.readTimeout(0)

            val minimum = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // The raw microphone on purpose: the phone's voice processing stacked
            // under a browser's own crushes the signal. Whatever records cleans up.
            @Suppress("MissingPermission")
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, FRAME_BYTES * 8))
            if (record.state != AudioRecord.STATE_INITIALIZED) throw LinkException("no_microphone")
            record.startRecording()

            setState(State.ON)
            notifyOngoing(Strings.MIC_ON + "  ·  " + opened.pc.name)
            onStreaming()

            val header = Messages.micAudio()
            val full = Protocol.head(header, FRAME_BYTES)
            val buffer = ByteArray(FRAME_BYTES)
            while (!stopping) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) throw java.io.IOException("microphone stopped")
                lastWrite = System.currentTimeMillis()
                opened.sendRaw(if (n == FRAME_BYTES) full else Protocol.head(header, n), buffer, n)
                lastWrite = 0L
            }
            runCatching { opened.send(Messages.micStop()) }
        } finally {
            lastWrite = 0L
            watchdog.interrupt()
            runCatching { record?.stop() }
            runCatching { record?.release() }
            opened.close()
            link = null
        }
    }

    /**
     * A write to a PC that vanished blocks for minutes before TCP gives up.
     * Closing the socket from here turns that into an error right away.
     */
    private fun watch(opened: Link) {
        try {
            while (!stopping) {
                Thread.sleep(1000)
                val since = lastWrite
                if (since != 0L && System.currentTimeMillis() - since > STALL_MS) {
                    opened.close()
                    return
                }
            }
        } catch (e: InterruptedException) {
        }
    }

    // --- state, notifications, locks ---------------------------------------

    private fun setState(next: State) {
        state = next
        main.post { MicModule.show() }
        TileService.requestListeningState(this, ComponentName(this, MicTile::class.java))
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, Strings.MIC_CHANNEL, NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun ongoing(text: String): Notification {
        channel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFF88C0D0.toInt())
            .addAction(Notification.Action.Builder(null, Strings.MIC_TURN_OFF, stop).build())
            .build()
    }

    private fun foreground(text: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ONGOING, ongoing(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ONGOING, ongoing(text))
        }
    }

    private fun notifyOngoing(text: String) {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(ONGOING, ongoing(text))
    }

    private fun notifyLost() {
        channel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(
            LOST,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(Strings.MIC_LOST)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun holdLocks() {
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "enlace:mic").apply { acquire() }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                   else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, "enlace:mic").apply { acquire() }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
    }
}
