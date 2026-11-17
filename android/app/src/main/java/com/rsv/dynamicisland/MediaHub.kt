package com.rsv.dynamicisland

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Tracks the active media sessions (fed by [NotifListener]) and exposes "now playing". */
object MediaHub {
    class Track(
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val artColor: Int,
        val durationMs: Long,
        val positionMs: Long,
        val positionAt: Long,
        val speed: Float,
        val playing: Boolean,
        val sessionActivity: PendingIntent?,
    ) {
        fun position(): Long {
            if (!playing) return positionMs
            val p = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    private const val PAUSE_GRACE_MS = 3 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val controllers = mutableListOf<MediaController>()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var controller: MediaController? = null
    private var pausedSince = 0L
    private var lastArtSource: Bitmap? = null
    private var lastArt: Bitmap? = null
    private var lastArtColor = Color.parseColor("#4FC3F7")

    var current: Track? = null
        private set

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** True when the island should show music: playing, or paused only briefly. */
    fun showing(): Boolean {
        val t = current ?: return false
        if (t.title.isEmpty()) return false
        return t.playing || (pausedSince != 0L && System.currentTimeMillis() - pausedSince < PAUSE_GRACE_MS)
    }

    fun setControllers(list: List<MediaController>?) {
        for (c in controllers) callbacks.remove(c.sessionToken)?.let { c.unregisterCallback(it) }
        controllers.clear()
        list?.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            c.registerCallback(cb, main)
            callbacks[c.sessionToken] = cb
            controllers.add(c)
        }
        refresh()
    }

    private fun isPlaying(c: MediaController): Boolean {
        val s = c.playbackState?.state ?: return false
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    private fun refresh() {
        val c = controllers.firstOrNull { isPlaying(it) } ?: controllers.firstOrNull { it.metadata != null }
        controller = c
        val md = c?.metadata
        if (c == null || md == null) {
            current = null
            pausedSince = 0
            notifyListeners()
            return
        }
        val st = c.playbackState
        val playing = isPlaying(c)
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val src = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (src !== lastArtSource) {
            lastArtSource = src
            lastArt = src?.let { shrink(it) }
            lastArtColor = lastArt?.let { accentFrom(it) } ?: Color.parseColor("#4FC3F7")
        }
        val prevPlaying = current?.playing ?: false
        if (playing) pausedSince = 0
        else if (prevPlaying || pausedSince == 0L) pausedSince = System.currentTimeMillis()

        current = Track(
            title = title,
            artist = artist,
            art = lastArt,
            artColor = lastArtColor,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = st?.position ?: 0,
            positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            playing = playing,
            sessionActivity = c.sessionActivity,
        )
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners.toList()) l()
    }

    private fun shrink(b: Bitmap): Bitmap {
        val max = 192
        if (b.width <= max && b.height <= max) return b
        val s = max.toFloat() / maxOf(b.width, b.height)
        return try {
            Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        } catch (_: Exception) { b }
    }

    /** Average colour of the cover, pushed bright enough to read on black. */
    private fun accentFrom(b: Bitmap): Int = try {
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(px, hsv)
        hsv[1] = (hsv[1] * 1.3f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        if (hsv[1] < 0.12f) Color.WHITE else Color.HSVToColor(hsv)
    } catch (_: Exception) { Color.parseColor("#4FC3F7") }

    fun playPause() {
        val c = controller ?: return
        if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun prev() { controller?.transportControls?.skipToPrevious() }
}
