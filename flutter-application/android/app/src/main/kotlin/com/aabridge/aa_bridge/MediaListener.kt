package com.aabridge.aa_bridge

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import io.flutter.plugin.common.EventChannel
import java.io.ByteArrayOutputStream

/**
 * Reads metadata from the currently-active MediaSession and forwards
 * snapshots over an EventChannel. Requires the NotificationListener permission
 * since MediaSessionManager.getActiveSessions checks the same gate.
 */
object MediaListener {
    private var controllers: List<MediaController> = emptyList()
    private var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var lastSourcePackage: String? = null
    private var linkConnected = false
    private var attached = false
    private var appContext: Context? = null
    // True once we've already pushed a fully-empty snapshot. Stops the
    // 1 Hz ticker from continually emitting "" / "" / "", which would
    // overwrite anything the test panel (or another producer) sent
    // directly through BleService.sendMedia. We still want a *single*
    // empty push when a real session ends, so the head unit clears
    // itself — that's why we don't drop empties unconditionally.
    private var lastWasEmpty = false

    fun streamHandler(ctx: Context): EventChannel.StreamHandler =
        object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                sink = events
                lastWasEmpty = false
                if (linkConnected) attach(ctx)
            }
            override fun onCancel(arguments: Any?) {
                sink = null
                stopTicker()
                detach()
            }
        }

    // The service can live for days waiting for the head unit. Do not run
    // a 1 Hz ticker, render artwork or observe players during that wait.
    fun setLinkConnected(ctx: Context, connected: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setLinkConnected(ctx, connected) }
            return
        }
        linkConnected = connected
        if (connected && sink != null) {
            if (!attached) {
                lastWasEmpty = false
                attach(ctx) // Sends the current player/cover immediately.
            }
        } else {
            stopTicker()
            detach()
        }
    }

    fun control(ctx: Context, cmd: String) {
        val c = activeController() ?: return
        when (cmd) {
            "play" -> c.transportControls.play()
            "pause" -> c.transportControls.pause()
            "next" -> c.transportControls.skipToNext()
            "prev" -> c.transportControls.skipToPrevious()
            else -> {}
        }
    }

    private fun activeController(): MediaController? =
        controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

    // Kept so detach() can unregister. The listener outlives attach(), and the
    // system holds it until removed — without this, every listener-service
    // reconnect (attach → detach → attach) left the previous one registered and
    // still pushing snapshots.
    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private fun attach(ctx: Context) {
        if (attached) return
        attached = true
        appContext = ctx.applicationContext
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val cn = ComponentName(ctx, NotifListener::class.java)
        try {
            controllers = msm.getActiveSessions(cn)
            controllers.forEach { it.registerCallback(callback) }
        } catch (_: SecurityException) {
            controllers = emptyList()
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
            controllers.forEach { it.unregisterCallback(callback) }
            controllers = list ?: emptyList()
            controllers.forEach { it.registerCallback(callback) }
            pushSnapshot()
        }
        sessionsListener = listener
        msm.addOnActiveSessionsChangedListener(listener, cn)
        startTicker()
        pushSnapshot()
    }

    private fun detach() {
        attached = false
        sessionsListener?.let { listener ->
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(listener)
            } catch (_: Exception) {}
        }
        sessionsListener = null
        sessionManager = null
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushSnapshot()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushSnapshot()
        override fun onSessionDestroyed() = pushSnapshot()
    }

    private fun startTicker() {
        stopTicker()
        ticker = object : Runnable {
            override fun run() {
                pushSnapshot()
                main.postDelayed(this, 1000)
            }
        }.also { main.postDelayed(it, 1000) }
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    private fun pushSnapshot() {
        val s = sink ?: return
        val c = activeController()
        if (c == null) {
            // Push exactly one empty snapshot per "no session" transition.
            // Subsequent ticker runs while nothing's playing stay silent
            // so they don't clobber data fed in via the test panel /
            // direct BleService.sendMedia.
            if (!lastWasEmpty) {
                s.success(emptySnapshot())
                lastWasEmpty = true
            }
            return
        }
        lastWasEmpty = false
        val md = c.metadata
        val ps = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = ps?.position ?: 0L
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val srcPkg = c.packageName
        // Album art only when the source changed or art changed, to keep
        // the snapshot ticker cheap.
        val rawBmp = extractArtwork(md)
        val isVideo = isVideoPlayback(srcPkg, rawBmp)
        val art = bitmapToJpeg(rawBmp, isVideo)

        lastSourcePackage = srcPkg
        s.success(mapOf(
            "title" to title,
            "artist" to artist,
            "album" to album,
            "durationMs" to dur,
            "positionMs" to pos,
            "isPlaying" to playing,
            "sourceApp" to srcPkg,
            "albumArt" to art,
        ))
    }

    private fun emptySnapshot() = mapOf(
        "title" to "", "artist" to "", "album" to "",
        "durationMs" to 0, "positionMs" to 0, "isPlaying" to false,
        "sourceApp" to "", "albumArt" to null,
    )

    private fun extractArtwork(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        val b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.description?.iconBitmap
        if (b != null) return b

        val uriStr = md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: md.description?.iconUri?.toString()

        if (!uriStr.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(uriStr)
                appContext?.contentResolver?.openInputStream(uri)?.use { stream ->
                    return BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun isVideoPlayback(pkg: String, b: Bitmap?): Boolean {
        val p = pkg.lowercase()
        // 1. Explicit video packages
        if (p.contains("youtube") && !p.contains("music")) return true
        if (p.contains(".video") || p.contains("videoplayer") || p.contains("newpipe")) return true
        if (p == "ru.rutube.app" || p == "com.kinopoisk" || p == "com.netflix.mediaclient" || p == "org.videolan.vlc") return true

        // 2. Explicit music packages
        if (p.contains("spotify") || p.contains("yandex.music") || p.contains("music") ||
            p.contains("deezer") || p.contains("soundcloud") || p.contains("audio") || p.contains("radio")) {
            return false
        }

        // 3. Aspect ratio or letterboxing
        if (b != null) {
            val aspect = b.width.toFloat() / b.height.toFloat()
            if (aspect >= 1.25f) return true
            if (isLetterboxed(b)) return true
        }
        return false
    }

    private fun isLetterboxed(b: Bitmap): Boolean {
        if (b.width != b.height || b.height < 64) return false
        val w = b.width
        val h = b.height
        val top = b.getPixel(w / 2, (h * 0.08f).toInt())
        val bot = b.getPixel(w / 2, (h * 0.92f).toInt())
        fun isDark(c: Int): Boolean {
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val bl = c and 0xff
            return r < 25 && g < 25 && bl < 25
        }
        return isDark(top) && isDark(bot)
    }

    private fun bitmapToJpeg(b: Bitmap?, isVideo: Boolean): ByteArray? {
        if (b == null) return null

        val dstW = if (isVideo) 352 else 128
        val dstH = if (isVideo) 144 else 128
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        var srcRect: Rect? = null
        if (isVideo && isLetterboxed(b)) {
            val cutH = (b.height * 0.20f).toInt()
            srcRect = Rect(0, cutH, b.width, b.height - cutH)
        }

        val sw = srcRect?.width() ?: b.width
        val sh = srcRect?.height() ?: b.height
        val sx = dstW.toFloat() / sw
        val sy = dstH.toFloat() / sh
        val scale = if (sx > sy) sx else sy
        val drawnW = sw * scale
        val drawnH = sh * scale

        val dstRect = Rect(
            ((dstW - drawnW) / 2f).toInt(),
            ((dstH - drawnH) / 2f).toInt(),
            ((dstW + drawnW) / 2f).toInt(),
            ((dstH + drawnH) / 2f).toInt()
        )
        canvas.drawBitmap(b, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))

        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
        out.recycle()
        return bytes.toByteArray()
    }

    // Helper for any future hardware-key relaying.
    @Suppress("unused")
    private fun keyEventDown(c: MediaController, key: Int) {
        c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        c.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }
}
