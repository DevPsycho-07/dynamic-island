package com.rsv.dynamicisland

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.rsv.dynamicisland.pet.PetEngine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The pill. One custom View, drawn entirely on Canvas.
 *
 * Priority of what shows ([computeLayout]):
 *   peek (notification, reply, timer done, pet asking / goal / evolve, volume, ringer,
 *   bluetooth, privacy, charging...)  >  expanded (call card, or swipeable pages
 *   Live · Media · Pet · Timer)  >  compact (call > timer > navigation > music > progress > pet).
 * Size changes ride a slightly bouncy spring; content cross-fades or slides. Nothing redraws
 * unless something is actually moving.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandView(ctx: Context, private val host: Host, private val field: ReplyField) : View(ctx) {

    interface Host {
        fun requestWindowSize(w: Int, h: Int)
        fun openApp()
        fun send(pi: PendingIntent?)
        fun setFocusable(focus: Boolean)
        /** Pull down the system notification shade (a swipe down that started on the island). */
        fun openShade()
    }

    /** The real text box lives in [IslandRoot]; this is how the island drives it. */
    interface ReplyField {
        fun showField(centerDx: Int, top: Int, w: Int, h: Int)
        fun hideField()
        fun fieldText(): String
        fun fieldHasWindowFocus(): Boolean
    }

    private enum class Layout(val big: Boolean, val page: Boolean = false) {
        DOCK(false), IDLE_C(false), MEDIA_C(false), TIMER_C(false), CALL_C(false), LIVE_C(false),
        VOLUME(false), INFO(false), LOW_BATT(false), CHARGE(false),
        PAGE_LIVE(true, true), PAGE_MEDIA(true, true), PAGE_DRIP(true, true), PAGE_TIMER(true, true),
        CALL_X(true), NOTIF(true), REPLY(true), TIMER_DONE(true), THIRSTY(true), SPLASH(true), GOAL(true), EVOLVE(true)
    }

    private enum class Peek(val prio: Int) {
        NONE(0), IDLE(1), VOLUME(1), INFO(2), CHARGE(2), LOW_BATT(3), SPLASH(3), NOTIF(3), THIRSTY(4), GOAL(4),
        EVOLVE(5), TIMER_DONE(5), REPLY(6)
    }

    private enum class Page { LIVE, MEDIA, DRIP, TIMER }
    enum class Glyph { HEADPHONES, WATCH, BLUETOOTH, CAMERA, MIC, BELL, BELL_OFF, VIBRATE, CHECK, CROSS, STAR }

    private enum class Act {
        PLAY_PAUSE, NEXT, PREV, DRINK, UNDO, SNOOZE, POKE, ADD100, ADD250, ADD500, CYCLE_TYPE,
        T5, T15, T25, T45, T_ADD1, T_CANCEL, T_ADD5, T_DISMISS, T_PAUSE, T_RESUME,
        PAGE, COPY, REPLY, SEND, CALL_END, CALL_SPEAKER, CALL_ANSWER
    }

    private class Hit(val r: RectF, val act: Act, val arg: Int = 0)

    private val app = ctx.applicationContext
    private val prefs = Prefs.get(ctx)
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun dp(v: Int) = v * d
    private fun on(key: String) = Prefs.on(prefs, key)

    // ---- geometry (from settings)
    private var compactH = 0f
    private var idleW = 0f

    // ---- what to show
    private var peek = Peek.NONE
    private var peekUntil = 0L
    private var notif: PeekNotif? = null
    private var replying: PeekNotif? = null
    private var copiedAt = 0L
    private var expanded = false
    private var expandedUntil = 0L
    private var callCard = false
    private var page = Page.DRIP
    private var pendingSlide = 0
    private var thirstyLine = ""
    private var splashAt = 0L
    private var chargeAt = 0L
    private var chargeFull = false
    private var batteryLevel = -1
    private var charging = false
    private var camOn = false
    private var micOn = false
    private var volFrac = 0f
    private var volShown = 0f
    private var volStream = AudioManager.STREAM_MUSIC
    private var infoGlyph = Glyph.CHECK
    private var infoLeft = ""
    private var infoRight = ""
    private var infoColor = BLUE
    private var infoLevel = -1
    private var lastDrinkLabel = ""
    private var touchX = 0f
    private var touchY = 0f
    private var touchAt = 0L

    // ---- animation state
    private var layout = Layout.IDLE_C
    private var prevLayout: Layout? = null
    private var prevW = 0f
    private var prevH = 0f
    private var switchAt = 0L
    private var slideDir = 0
    private var tw = 0f; private var th = 0f; private var tr = 0f; private var tt = 0f
    private var cw = 0f; private var ch = 0f; private var cr = 0f; private var ct = 0f
    private var vw = 0f; private var vh = 0f; private var vr = 0f; private var vt = 0f
    private var prevTop = 0f
    /** Punch-hole diameter in px (0 = unknown). */
    private var holeD = 0f
    private var shadeFired = false
    private var downOnShape = false
    private var moving = false
    private var lastDraw = 0L
    private var winW = 0
    private var winH = 0
    val windowW get() = winW
    val windowH get() = winH

    private var moodCache = Mood.IDLE
    private var moodAt = 0L

    // ---- drawing tools
    private val hits = ArrayList<Hit>()
    private var recording = false
    private val pill = RectF()
    private val clip = Path()
    private val artClip = Path()
    private val icon = Path()
    private val liquid = Path()
    private val rA = RectF()
    private val rB = RectF()
    private val tmp = RectF()
    private val art = RectF()
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tMain = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = medium }
    private val tSub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
    private val tRight = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = medium }
    private val tBtn = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = medium }

    private val expiry = Runnable { refresh() }
    private val tickR = Runnable { invalidate() }
    private val showFieldR = Runnable { showReplyField() }
    private val replyFallback = Runnable {
        // Some ROMs won't give an overlay the keyboard. Then just open the conversation instead.
        if (peek == Peek.REPLY && !field.fieldHasWindowFocus()) {
            val pi = replying?.intent
            endReply()
            collapse()
            host.send(pi)
        }
    }

    init {
        reloadGeometry()
    }

    // =====================================================================================
    // Public API (called by IslandService / IslandRoot)
    // =====================================================================================

    fun reloadGeometry() {
        compactH = dp(prefs.getInt(Prefs.HEIGHT, Prefs.DEF_HEIGHT))
        idleW = dp(prefs.getInt(Prefs.WIDTH, Prefs.DEF_WIDTH))
        refresh(force = true)
    }

    fun setBattery(level: Int, isCharging: Boolean) {
        batteryLevel = level
        charging = isCharging
    }

    fun setPrivacy(camera: Boolean, mic: Boolean) {
        if (camera == camOn && mic == micOn) return
        camOn = camera
        micOn = mic
        refresh() // the dock widens to fit the dot
    }

    /** Camera cutout diameter, so the dock covers exactly the punch hole. */
    fun setHole(px: Float) {
        holeD = px
        refresh(force = true)
    }

    fun showNotification(n: PeekNotif) {
        notif = n
        copiedAt = 0
        setPeek(Peek.NOTIF, if (n.otp != null || n.reply != null) 8000 else 4500)
    }

    fun showCharging(full: Boolean) {
        if (!on(Prefs.PEEK_CHARGING)) return
        chargeFull = full
        chargeAt = SystemClock.uptimeMillis()
        setPeek(Peek.CHARGE, if (full) 3000 else 3600)
    }

    fun showLowBattery() {
        chargeAt = SystemClock.uptimeMillis()
        setPeek(Peek.LOW_BATT, 4000)
    }

    fun showTimerDone() = setPeek(Peek.TIMER_DONE, 20_000)

    fun showThirsty() {
        moodAt = 0
        thirstyLine = Water.askLine(Water.mood(app))
        setPeek(Peek.THIRSTY, 15_000)
    }

    fun showSplash() {
        moodAt = 0
        if (peek == Peek.THIRSTY) peek = Peek.NONE // drinking answers the nag
        splashAt = SystemClock.uptimeMillis()
        val last = Water.entries(app).lastOrNull()
        lastDrinkLabel = if (last != null) "+${last.ml} ml ${last.drink.label}" else "+${Water.cup(prefs)} ml"
        setPeek(Peek.SPLASH, 3200)
    }

    fun showGoal() {
        moodAt = 0
        if (peek == Peek.THIRSTY) peek = Peek.NONE
        setPeek(Peek.GOAL, 3600)
    }

    fun showEvolve() {
        moodAt = 0
        setPeek(Peek.EVOLVE, 4000)
    }

    fun showVolume(stream: Int, frac: Float) {
        if (peek == Peek.NONE || peek == Peek.VOLUME) {
            if (peek != Peek.VOLUME) volShown = frac
        }
        volStream = stream
        volFrac = frac.coerceIn(0f, 1f)
        setPeek(Peek.VOLUME, 1500)
    }

    fun showInfo(g: Glyph, left: String, right: String, color: Int, ms: Long, level: Int = -1) {
        val saved = arrayOf(infoGlyph, infoLeft, infoRight, infoColor, infoLevel)
        infoGlyph = g; infoLeft = left; infoRight = right; infoColor = color; infoLevel = level
        if (!setPeek(Peek.INFO, ms)) { // lost to a more important peek: restore
            infoGlyph = saved[0] as Glyph; infoLeft = saved[1] as String; infoRight = saved[2] as String
            infoColor = saved[3] as Int; infoLevel = saved[4] as Int
        }
    }

    /** Battery level for a Bluetooth device often arrives a moment after it connects. */
    fun updateInfoLevel(left: String, level: Int) {
        if (peek == Peek.INFO && infoLeft == left) {
            infoLevel = level
            infoRight = "$level%"
            invalidate()
        }
    }

    fun dismissThirsty() {
        if (peek == Peek.THIRSTY) {
            peek = Peek.NONE
            refresh()
        }
    }

    fun collapse() {
        if (peek == Peek.REPLY) endReply()
        peek = Peek.NONE
        notif = null
        expanded = false
        refresh()
    }

    fun onOutsideTouch() {
        if (peek == Peek.REPLY) cancelReply()
        else if (peek != Peek.NONE || expanded) collapse()
    }

    fun onScreenOff() {
        if (peek == Peek.REPLY) endReply()
        peek = Peek.NONE
        notif = null
        expanded = false
        refresh()
        cw = tw; ch = th; cr = tr; ct = tt
        vw = 0f; vh = 0f; vr = 0f; vt = 0f
        moving = false
        prevLayout = null
        shrinkWindow()
    }

    /** Re-evaluate what to show (data changed, timeouts, etc.). Cheap. */
    fun refresh(force: Boolean = false) {
        moodAt = 0
        val l = computeLayout()
        val s = sizeOf(l)
        val top = topOf(l, s)
        if (l != layout || force || s[0] != tw || s[1] != th || top != tt) {
            if (l != layout) {
                prevLayout = layout
                prevW = tw
                prevH = th
                prevTop = tt
                switchAt = SystemClock.uptimeMillis()
                slideDir = if (layout.page && l.page) pendingSlide else 0
                pendingSlide = 0
            }
            layout = l
            tw = s[0]; th = s[1]; tr = s[2]; tt = top
            if (cw == 0f) {
                cw = tw; ch = th; cr = tr; ct = tt
            }
            moving = true
            growWindow()
        }
        scheduleExpiry()
        invalidate()
    }

    // =====================================================================================
    // Inline reply
    // =====================================================================================

    private fun startReply() {
        val n = notif ?: return
        if (n.reply == null) return
        replying = n
        setPeek(Peek.REPLY, 120_000)
        host.setFocusable(true)
        removeCallbacks(showFieldR)
        postDelayed(showFieldR, 220)
        removeCallbacks(replyFallback)
        postDelayed(replyFallback, 1200)
    }

    private fun showReplyField() {
        if (peek != Peek.REPLY) return
        field.showField(
            centerDx = (-dp(22)).toInt(),
            top = (compactH + dp(44)).toInt(),
            w = (tw - dp(76)).toInt(),
            h = dp(40).toInt(),
        )
    }

    fun sendReply() {
        val n = replying ?: return
        val a = n.reply ?: return
        val text = field.fieldText().trim()
        if (text.isEmpty()) return
        val ok = try {
            val intent = Intent()
            val b = Bundle()
            val inputs = a.remoteInputs ?: emptyArray()
            for (ri in inputs) b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, b)
            a.actionIntent.send(app, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
        endReply()
        peek = Peek.NONE
        if (ok) showInfo(Glyph.CHECK, "Reply sent", n.title, GREEN, 1600)
        else showInfo(Glyph.CROSS, "Couldn't send", "open the app", RED, 2200)
    }

    fun cancelReply() {
        if (peek != Peek.REPLY) return
        endReply()
        collapse()
    }

    private fun endReply() {
        removeCallbacks(showFieldR)
        removeCallbacks(replyFallback)
        field.hideField()
        host.setFocusable(false)
        replying = null
        if (peek == Peek.REPLY) peek = Peek.NONE
    }

    // =====================================================================================
    // State -> layout
    // =====================================================================================

    private enum class Content { CALL, TIMER, NAV, MEDIA, LIVE, IDLE }

    private fun content(): Content = when {
        on(Prefs.F_CALL) && LiveHub.call != null -> Content.CALL
        IslandTimer.active(app) -> Content.TIMER
        on(Prefs.F_LIVE) && LiveHub.nav() != null -> Content.NAV
        // Docked: paused music doesn't keep the pill open (still reachable on the Media page).
        on(Prefs.F_MEDIA) && (if (dockOn()) MediaHub.current?.playing == true else MediaHub.showing()) -> Content.MEDIA
        on(Prefs.F_LIVE) && LiveHub.progressItem() != null -> Content.LIVE
        else -> Content.IDLE
    }

    private fun pages(): List<Page> {
        val list = ArrayList<Page>(4)
        if (on(Prefs.F_LIVE) && LiveHub.top() != null) list.add(Page.LIVE)
        if (on(Prefs.F_MEDIA) && MediaHub.current != null) list.add(Page.MEDIA)
        list.add(Page.DRIP)
        list.add(Page.TIMER)
        return list
    }

    private fun computeLayout(): Layout {
        val now = System.currentTimeMillis()
        if (peek != Peek.NONE && now >= peekUntil) {
            if (peek == Peek.REPLY) endReply()
            peek = Peek.NONE
            notif = null
        }
        if (expanded && now >= expandedUntil) expanded = false
        when (peek) {
            Peek.IDLE -> return Layout.IDLE_C
            Peek.NOTIF -> return Layout.NOTIF
            Peek.REPLY -> return Layout.REPLY
            Peek.CHARGE -> return Layout.CHARGE
            Peek.LOW_BATT -> return Layout.LOW_BATT
            Peek.TIMER_DONE -> return Layout.TIMER_DONE
            Peek.THIRSTY -> return Layout.THIRSTY
            Peek.SPLASH -> return Layout.SPLASH
            Peek.GOAL -> return Layout.GOAL
            Peek.EVOLVE -> return Layout.EVOLVE
            Peek.VOLUME -> return Layout.VOLUME
            Peek.INFO -> return Layout.INFO
            Peek.NONE -> {}
        }
        val c = content()
        if (expanded) {
            if (callCard && c == Content.CALL) return Layout.CALL_X
            val ps = pages()
            if (page !in ps) page = Page.DRIP
            return when (page) {
                Page.LIVE -> Layout.PAGE_LIVE
                Page.MEDIA -> Layout.PAGE_MEDIA
                Page.DRIP -> Layout.PAGE_DRIP
                Page.TIMER -> Layout.PAGE_TIMER
            }
        }
        return when (c) {
            Content.CALL -> Layout.CALL_C
            Content.TIMER -> Layout.TIMER_C
            Content.NAV, Content.LIVE -> Layout.LIVE_C
            Content.MEDIA -> Layout.MEDIA_C
            Content.IDLE -> if (dockOn()) Layout.DOCK else Layout.IDLE_C
        }
    }

    private fun dockOn() = on(Prefs.F_DOCK)

    /** Black disc that just covers the punch hole (+2 dp each side), never taller than the pill. */
    private fun dockD(): Float {
        val d = if (holeD > 0f) holeD + dp(4) else compactH - dp(8)
        return d.coerceIn(dp(14), compactH)
    }

    private fun dotCount() = (if (camOn) 1 else 0) + (if (micOn) 1 else 0)

    /** The dock sits on the camera's centre, which is the compact pill's vertical centre. */
    private fun topOf(l: Layout, s: FloatArray): Float = if (l == Layout.DOCK) (compactH - s[1]) / 2f else 0f

    private fun screenW(): Float {
        val m = resources.displayMetrics
        return min(m.widthPixels, m.heightPixels).toFloat()
    }

    /** [width, height, corner radius] for a layout. */
    private fun sizeOf(l: Layout): FloatArray {
        val full = min(screenW() - dp(16), dp(440))
        val h = compactH
        val xr = dp(34)
        fun c(w: Float) = floatArrayOf(min(full, w), h, h / 2)
        fun x(extra: Float) = floatArrayOf(full, h + extra, xr)
        return when (l) {
            Layout.DOCK -> {
                val d = dockD()
                val n = dotCount()
                floatArrayOf(if (n == 0) d else d + 2f * (dp(5) + n * dp(9)), d, d / 2f)
            }
            Layout.IDLE_C -> c(idleW + dp(56))
            Layout.MEDIA_C -> c(idleW + dp(84))
            Layout.TIMER_C -> c(idleW + dp(70))
            Layout.CALL_C -> c(idleW + dp(110))
            Layout.LIVE_C -> c(idleW + if (LiveHub.top()?.nav == true) dp(140) else dp(76))
            Layout.VOLUME -> c(idleW + dp(130))
            Layout.INFO, Layout.LOW_BATT -> c(idleW + dp(170))
            Layout.CHARGE -> floatArrayOf(min(full, idleW + dp(190)), h + dp(40), min(xr, (h + dp(40)) / 2))
            Layout.PAGE_LIVE -> x(dp(100))
            Layout.PAGE_MEDIA -> x(dp(150))
            Layout.PAGE_DRIP -> x(dp(150))
            Layout.PAGE_TIMER -> x(dp(104))
            Layout.CALL_X -> x(dp(84))
            Layout.NOTIF -> x(if (notif?.otp != null || notif?.reply != null) dp(88) else dp(50))
            Layout.REPLY -> x(dp(100))
            Layout.TIMER_DONE -> x(dp(68))
            Layout.THIRSTY -> x(dp(96))
            Layout.SPLASH -> x(dp(72))
            Layout.GOAL, Layout.EVOLVE -> x(dp(80))
        }
    }

    /** Returns false when a more important peek is already showing. */
    private fun setPeek(p: Peek, ms: Long): Boolean {
        val now = System.currentTimeMillis()
        if (peek != Peek.NONE && now < peekUntil && p.prio < peek.prio) return false
        if (peek == Peek.REPLY && p != Peek.REPLY) return false
        peek = p
        peekUntil = now + ms
        expanded = false
        refresh()
        return true
    }

    private fun expand() {
        peek = Peek.NONE
        expanded = true
        val c = content()
        callCard = c == Content.CALL
        page = when (c) {
            Content.TIMER -> Page.TIMER
            Content.NAV, Content.LIVE -> Page.LIVE
            Content.MEDIA -> Page.MEDIA
            else -> Page.DRIP
        }
        expandedUntil = System.currentTimeMillis() + if (c == Content.MEDIA) 8000 else 6500
        refresh()
    }

    private fun goPage(dir: Int) {
        val ps = pages()
        val i = ps.indexOf(page)
        val j = i + dir
        if (i < 0 || j !in ps.indices) return
        page = ps[j]
        pendingSlide = dir
        expandedUntil = System.currentTimeMillis() + 7000
        refresh()
    }

    private fun extendTimeouts() {
        val now = System.currentTimeMillis()
        if (peek != Peek.NONE && peek != Peek.REPLY) peekUntil = max(peekUntil, now + 3000)
        if (expanded) expandedUntil = max(expandedUntil, now + 5000)
        scheduleExpiry()
    }

    private fun scheduleExpiry() {
        removeCallbacks(expiry)
        var next = Long.MAX_VALUE
        if (peek != Peek.NONE) next = min(next, peekUntil)
        if (expanded) next = min(next, expandedUntil)
        if (next != Long.MAX_VALUE) postDelayed(expiry, (next - System.currentTimeMillis()).coerceAtLeast(16))
    }

    private fun mood(): Mood {
        val now = System.currentTimeMillis()
        if (now - moodAt > 2000) {
            moodCache = Water.mood(app, now)
            moodAt = now
        }
        return moodCache
    }

    private fun waterFrac(): Float = Water.progress(app)

    private fun dancing() = on(Prefs.F_DANCE) && MediaHub.current?.playing == true

    /** Where the pet should look: towards the last touch, for 2.5 s. */
    private fun look(cx: Float, cy: Float): FloatArray {
        if (SystemClock.uptimeMillis() - touchAt > 2500) return ZERO2
        val dx = touchX - cx
        val dy = touchY - cy
        val len = hypot(dx, dy).coerceAtLeast(1f)
        return floatArrayOf(dx / len, dy / len)
    }


    // =====================================================================================
    // Window size: grow before an expansion, shrink after a collapse settles.
    // =====================================================================================

    /**
     * The window WIDTH never changes: it is always the widest the island can get (+ slop, capped
     * at the screen). Resizing the width mid-morph made the pill hop sideways for a frame while
     * the window re-centred; only the height changes now, and a height change can't move
     * anything sideways. The pill is drawn centred at width / 2.
     */
    private fun constW(): Float {
        val sw = screenW()
        return min(sw, min(sw - dp(16), dp(440)) + dp(24))
    }

    private fun requestWin(h: Float) {
        val iw = ceil(constW()).toInt()
        val ih = ceil(h).toInt()
        if (iw != winW || ih != winH) {
            winW = iw
            winH = ih
            host.requestWindowSize(iw, ih)
        }
    }

    /** Before a morph: make sure the window is tall enough for it (height only). */
    private fun growWindow() {
        val h = max(tt + th, ct + ch) * 1.06f + dp(4)
        if (h > winH || winW == 0) requestWin(max(h, winH.toFloat()))
    }

    /** After the morph settles: shrink the height to the shape so swipes below go to the system. */
    private fun shrinkWindow() {
        if (layout == Layout.DOCK) requestWin(tt + th + dp(5))
        else requestWin(tt + th * 1.02f + dp(4))
    }

    /** The drawn shape, a little inflated for fingers. Touches outside it are on transparent window. */
    private fun onShape(x: Float, y: Float): Boolean =
        x >= pill.left - dp(6) && x <= pill.right + dp(6) && y >= pill.top - dp(6) && y <= pill.bottom + dp(6)

    // =====================================================================================
    // Animation
    // =====================================================================================

    private fun step(dt: Float) {
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 120f)
            vw += ((tw - cw) * K - vw * C) * h; cw += vw * h
            vh += ((th - ch) * K - vh * C) * h; ch += vh * h
            vr += ((tr - cr) * K - vr * C) * h; cr += vr * h
            vt += ((tt - ct) * K - vt * C) * h; ct += vt * h
            left -= h
        }
        if (abs(tw - cw) < 0.5f && abs(th - ch) < 0.5f && abs(tr - cr) < 0.5f && abs(tt - ct) < 0.5f &&
            abs(vw) < 4f && abs(vh) < 4f
        ) {
            cw = tw; ch = th; cr = tr; ct = tt
            vw = 0f; vh = 0f; vr = 0f; vt = 0f
            moving = false
            shrinkWindow()
        }
    }

    private fun scheduleNextFrame(now: Long) {
        val wall = System.currentTimeMillis()
        val fast = moving || prevLayout != null || PetEngine.busy() || PetEngine.pokeShowing() || when (layout) {
            Layout.PAGE_DRIP, Layout.THIRSTY, Layout.SPLASH, Layout.TIMER_DONE, Layout.CHARGE, Layout.GOAL, Layout.EVOLVE -> true
            Layout.VOLUME -> abs(volShown - volFrac) > 0.004f
            Layout.LOW_BATT -> now - chargeAt < 1000
            else -> false
        }
        removeCallbacks(tickR)
        if (fast) {
            postInvalidateOnAnimation()
            return
        }
        val call = LiveHub.call
        val delay: Long = when (layout) {
            Layout.MEDIA_C, Layout.PAGE_MEDIA -> if (MediaHub.current?.playing == true) 66L else -1L
            Layout.TIMER_C, Layout.PAGE_TIMER -> if (IslandTimer.running(app)) 1000L - wall % 1000L + 5L else -1L
            Layout.CALL_C, Layout.CALL_X -> if (call?.incoming == true) 50L else 1000L - wall % 1000L + 5L
            Layout.LIVE_C, Layout.PAGE_LIVE -> if (LiveHub.top()?.let { it.indeterminate && !it.nav } == true) 50L else -1L
            Layout.IDLE_C -> if (mood() == Mood.SLEEPING) -1L else PetEngine.msToNextBlink(now)
            else -> -1L
        }
        when {
            delay == 0L -> postInvalidateOnAnimation()
            delay > 0L -> postDelayed(tickR, delay)
        }
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDraw == 0L) 0.016f else ((now - lastDraw) / 1000f).coerceIn(0.001f, 0.034f)
        lastDraw = now
        if (moving) step(dt)
        if (layout == Layout.VOLUME) volShown += (volFrac - volShown) * min(1f, dt * 14f)

        val cx = width / 2f
        pill.set(cx - cw / 2f, ct, cx + cw / 2f, ct + ch)
        val r = min(cr, ch / 2f).coerceAtLeast(0f)
        c.drawRoundRect(pill, r, r, black)

        clip.reset()
        clip.addRoundRect(pill, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        hits.clear()
        val sliding = slideDir != 0 && now - switchAt < SLIDE_MS
        val e = ease(((now - switchAt) / SLIDE_MS.toFloat()).coerceIn(0f, 1f))
        prevLayout?.let { pl ->
            val a = if (slideDir != 0) 1f - e else 1f - (now - switchAt) / 140f
            if (a > 0f && (sliding || slideDir == 0)) {
                rA.set(cx - prevW / 2f, prevTop, cx + prevW / 2f, prevTop + prevH)
                drawContent(c, pl, rA, a, record = false, dx = if (slideDir != 0) -slideDir * e * prevW * 0.5f else 0f)
            } else {
                prevLayout = null
                slideDir = 0
            }
        }
        val a = if (sliding) e else {
            val dist = max(abs(cw - tw) / max(tw, 1f), abs(ch - th) / max(th, 1f))
            (1f - dist * 5f).coerceIn(0f, 1f)
        }
        if (a > 0f) {
            rB.set(cx - tw / 2f, tt, cx + tw / 2f, tt + th)
            drawContent(c, layout, rB, a, record = true, dx = if (sliding) slideDir * (1f - e) * tw * 0.5f else 0f)
        }
        c.restore()
        if (!layout.big && layout != Layout.DOCK) drawPrivacyDots(c, cx, ch)
        scheduleNextFrame(now)
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun drawContent(c: Canvas, l: Layout, r: RectF, alpha: Float, record: Boolean, dx: Float) {
        val layer = alpha < 0.99f
        if (layer) c.saveLayerAlpha(r.left, r.top, r.right, r.bottom, (alpha * 255).toInt())
        if (dx != 0f) {
            c.save()
            c.translate(dx, 0f)
        }
        recording = record
        when (l) {
            Layout.DOCK -> drawDock(c, r)
            Layout.IDLE_C -> drawIdleCompact(c, r)
            Layout.MEDIA_C -> drawMediaCompact(c, r)
            Layout.TIMER_C -> drawTimerCompact(c, r)
            Layout.CALL_C -> drawCallCompact(c, r)
            Layout.LIVE_C -> drawLiveCompact(c, r)
            Layout.VOLUME -> drawVolume(c, r)
            Layout.INFO -> drawInfo(c, r)
            Layout.LOW_BATT -> drawLowBattery(c, r)
            Layout.CHARGE -> drawCharge(c, r)
            Layout.PAGE_LIVE -> { drawLivePage(c, r); dots(c, r, Page.LIVE) }
            Layout.PAGE_MEDIA -> { drawMediaPage(c, r); dots(c, r, Page.MEDIA) }
            Layout.PAGE_DRIP -> { drawDripPage(c, r); dots(c, r, Page.DRIP) }
            Layout.PAGE_TIMER -> { drawTimerPage(c, r); dots(c, r, Page.TIMER) }
            Layout.CALL_X -> drawCallCard(c, r)
            Layout.NOTIF -> drawNotif(c, r)
            Layout.REPLY -> drawReply(c, r)
            Layout.TIMER_DONE -> drawTimerDone(c, r)
            Layout.THIRSTY -> drawThirsty(c, r)
            Layout.SPLASH -> drawSplash(c, r)
            Layout.GOAL -> drawCelebration(c, r, evolved = false)
            Layout.EVOLVE -> drawCelebration(c, r, evolved = true)
        }
        recording = false
        if (dx != 0f) c.restore()
        if (layer) c.restore()
    }

    /**
     * Docked: only the camera shows. A 1.5 dp water-blue hairline between the lens and the
     * disc's edge shows today's progress; privacy dots sit just right of the lens.
     */
    private fun drawDock(c: Canvas, r: RectF) {
        val d = r.height()
        val cx = r.centerX()
        val cy = r.top + d / 2f
        val rad = d / 2f - dp(0.75f)
        // Starts at 90° (bottom, 6 o'clock) and sweeps clockwise: up the left side, over the top, down the right.
        ring(c, cx, cy, rad, dp(1.5f), 0x553A6F85, BLUE, waterFrac(), start = 90f)
        var x = cx + d / 2f + dp(5) + dp(3)
        if (camOn) {
            fillP.color = GREEN
            c.drawCircle(x, cy, dp(3), fillP)
            x += dp(9)
        }
        if (micOn) {
            fillP.color = ORANGE
            c.drawCircle(x, cy, dp(3), fillP)
        }
    }

    /** Green (camera) / orange (mic) dots just right of the camera, like the privacy indicators. */
    private fun drawPrivacyDots(c: Canvas, cx: Float, h: Float) {
        if (!camOn && !micOn) return
        val y = min(h, compactH) / 2f
        var x = cx + dp(16)
        if (camOn) {
            fillP.color = GREEN
            c.drawCircle(x, y, dp(3), fillP)
            x += dp(9)
        }
        if (micOn) {
            fillP.color = ORANGE
            c.drawCircle(x, y, dp(3), fillP)
        }
    }

    private fun dots(c: Canvas, r: RectF, current: Page) {
        val ps = pages()
        if (ps.size < 2) return
        val gap = dp(12)
        val y = r.bottom - dp(10)
        var x = r.centerX() - gap * (ps.size - 1) / 2f
        for ((i, p) in ps.withIndex()) {
            fillP.color = if (p == current) Color.WHITE else 0xFF4A4A4A.toInt()
            c.drawCircle(x, y, dp(3), fillP)
            if (recording) hits.add(Hit(RectF(x - gap / 2f, y - dp(10), x + gap / 2f, y + dp(8)), Act.PAGE, i))
            x += gap
        }
    }

    // ---- compact -------------------------------------------------------------------------

    /** Compact pet face on the left, "1.2/2.0 L" on the right. */
    private fun drawIdleCompact(c: Canvas, r: RectF) {
        val h = r.height()
        val px = r.left + h / 2f + dp(2)
        val lk = look(px, h / 2f)
        PetEngine.draw(c, app, px, h / 2f, h * 0.86f, compact = true, lookX = lk[0], lookY = lk[1])
        val ml = Water.todayMl(app)
        val goal = Water.goal(prefs)
        tRight.textSize = h * 0.42f
        tRight.color = if (ml >= goal) GREEN else Color.WHITE
        val right = r.right - dp(13)
        val total = "/${Water.liters(goal)} L"
        c.drawText(total, right, h / 2f + tRight.textSize * 0.36f, tRight.apply { color = GREY })
        tRight.color = if (ml >= goal) GREEN else BLUE
        c.drawText(Water.liters(ml), right - tRight.measureText(total), h / 2f + tRight.textSize * 0.36f, tRight)
    }

    private fun drawMediaCompact(c: Canvas, r: RectF) {
        val t = MediaHub.current
        val h = r.height()
        val s = h - dp(8)
        art.set(r.left + dp(6), dp(4), r.left + dp(6) + s, dp(4) + s)
        drawArt(c, t?.art, art, dp(7))
        eq(c, r.right - dp(14), h / 2f, h * 0.48f, t?.artColor ?: BLUE, t?.playing == true)
    }

    private fun drawTimerCompact(c: Canvas, r: RectF) {
        val h = r.height()
        val rem = IslandTimer.remainingMs(app)
        val total = IslandTimer.totalMs(prefs).coerceAtLeast(1)
        val paused = IslandTimer.paused(app)
        val rx = r.left + h / 2f + dp(3)
        if (paused) {
            fillP.color = ORANGE
            val bw = h * 0.1f
            tmp.set(rx - h * 0.16f, h * 0.3f, rx - h * 0.16f + bw, h * 0.7f)
            c.drawRoundRect(tmp, bw / 2, bw / 2, fillP)
            tmp.set(rx + h * 0.06f, h * 0.3f, rx + h * 0.06f + bw, h * 0.7f)
            c.drawRoundRect(tmp, bw / 2, bw / 2, fillP)
        } else {
            ring(c, rx, h / 2f, h * 0.27f, dp(2.6f), 0xFF3A2A12.toInt(), ORANGE, rem.toFloat() / total)
        }
        tRight.color = if (paused) 0xFFB07A2A.toInt() else ORANGE
        tRight.textSize = h * 0.5f
        c.drawText(IslandTimer.format(rem), r.right - dp(12), h / 2f + tRight.textSize * 0.36f, tRight)
    }

    private fun drawCallCompact(c: Canvas, r: RectF) {
        val call = LiveHub.call ?: return
        val h = r.height()
        val cy = h / 2f
        val pulse = if (call.incoming) 0.55f + 0.45f * abs(sin(SystemClock.uptimeMillis() / 300.0)).toFloat() else 1f
        phone(c, r.left + h / 2f + dp(3), cy, h * 0.5f, GREEN, pulse)
        val x = r.left + h + dp(2)
        tMain.textSize = dp(13)
        text(c, call.name, x, cy + dp(4.5f), tMain, r.centerX() - dp(18) - x)
        tRight.color = GREEN
        tRight.textSize = dp(13.5f)
        val right = if (call.incoming) "Incoming" else clock(System.currentTimeMillis() - call.startedAt)
        c.drawText(right, r.right - dp(13), cy + dp(4.8f), tRight)
    }

    private fun drawLiveCompact(c: Canvas, r: RectF) {
        val item = LiveHub.top() ?: return
        val h = r.height()
        val cy = h / 2f
        val s = h - dp(8)
        drawIcon(c, item.icon, r.left + dp(5), dp(4), s)
        if (item.nav) {
            tRight.color = Color.WHITE
            tRight.textSize = dp(13)
            val maxW = r.right - dp(12) - (r.centerX() + dp(18))
            val e = TextUtils.ellipsize(item.title, tRight, maxW, TextUtils.TruncateAt.END)
            c.drawText(e, 0, e.length, r.right - dp(12), cy + dp(4.5f), tRight)
        } else {
            val rr = h * 0.27f
            val rx = r.right - h / 2f - dp(3)
            if (item.indeterminate) spinner(c, rx, cy, rr, BLUE) else ring(c, rx, cy, rr, dp(2.6f), 0xFF1E3A47.toInt(), BLUE, item.frac)
            if (!item.indeterminate) {
                tRight.color = GREY
                tRight.textSize = dp(11.5f)
                c.drawText("${(item.frac * 100).toInt()}%", rx - rr - dp(6), cy + dp(4), tRight)
            }
        }
    }

    private fun drawVolume(c: Canvas, r: RectF) {
        val h = r.height()
        val cy = h / 2f
        speaker(c, r.left + h / 2f + dp(3), cy, h * 0.5f, Color.WHITE, volFrac)
        tSub.textSize = dp(12)
        val label = when (volStream) {
            AudioManager.STREAM_RING -> "Ring"
            AudioManager.STREAM_VOICE_CALL -> "Call"
            AudioManager.STREAM_ALARM -> "Alarm"
            else -> "Media"
        }
        text(c, label, r.left + h + dp(2), cy + dp(4), tSub, r.centerX() - dp(18) - (r.left + h + dp(2)))
        bar(c, r.centerX() + dp(18), cy - dp(3), r.right - dp(14), dp(6), volShown, Color.WHITE)
    }

    private fun drawInfo(c: Canvas, r: RectF) {
        val h = r.height()
        val cy = h / 2f
        glyph(c, infoGlyph, r.left + h / 2f + dp(4), cy, h * 0.56f, infoColor)
        if (infoLeft.isNotEmpty()) {
            val x = r.left + h + dp(4)
            tMain.textSize = dp(13)
            text(c, infoLeft, x, cy + dp(4.5f), tMain, r.centerX() - dp(18) - x)
        }
        var right = r.right - dp(14)
        if (infoLevel >= 0) {
            val bw = dp(22)
            batteryGlyph(c, right - bw, cy, bw, dp(11), infoLevel / 100f, if (infoLevel <= 20) RED else GREEN)
            right -= bw + dp(6)
        }
        tRight.color = infoColor
        tRight.textSize = dp(13)
        val maxW = right - (r.centerX() + dp(18))
        val e = TextUtils.ellipsize(infoRight, tRight, maxW, TextUtils.TruncateAt.END)
        c.drawText(e, 0, e.length, right, cy + dp(4.5f), tRight)
    }

    private fun drawLowBattery(c: Canvas, r: RectF) {
        val h = r.height()
        val cy = h / 2f
        tMain.textSize = dp(13.5f)
        c.drawText("Low Battery", r.left + dp(14), cy + dp(4.8f), tMain)
        val level = batteryLevel.coerceAtLeast(0)
        val bw = dp(25)
        val bx = r.right - dp(14) - bw
        batteryGlyph(c, bx, cy, bw, dp(12), level / 100f, RED)
        tRight.color = RED
        tRight.textSize = dp(13.5f)
        c.drawText("$level%", bx - dp(7), cy + dp(4.8f), tRight)
    }

    /** Liquid-fill battery: the green fill rises with a moving wavy edge. */
    private fun drawCharge(c: Canvas, r: RectF) {
        val h = r.height()
        val cy = h / 2f
        val now = SystemClock.uptimeMillis()
        val k = ease(((now - chargeAt) / 1200f).coerceIn(0f, 1f))
        val level = if (chargeFull) 100 else batteryLevel.coerceIn(0, 100)
        val frac = level / 100f * k
        val bw = dp(56)
        val bh = dp(28)
        val bx = r.left + dp(18)
        val body = RectF(bx, cy - bh / 2f, bx + bw, cy + bh / 2f)
        strokeP.color = 0xFF8A8A8A.toInt()
        strokeP.strokeWidth = dp(1.6f)
        c.drawRoundRect(body, dp(7), dp(7), strokeP)
        fillP.color = 0xFF8A8A8A.toInt()
        tmp.set(body.right + dp(2), cy - bh * 0.18f, body.right + dp(5), cy + bh * 0.18f)
        c.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fillP)

        val inner = RectF(body.left + dp(3), body.top + dp(3), body.right - dp(3), body.bottom - dp(3))
        c.save()
        artClip.reset()
        artClip.addRoundRect(inner, dp(4.5f), dp(4.5f), Path.Direction.CW)
        c.clipPath(artClip)
        val edge = inner.left + inner.width() * frac
        val amp = dp(2.2f) * (if (frac in 0.02f..0.98f) 1f else 0f)
        val ph = now / 160.0
        liquid.reset()
        liquid.moveTo(inner.left - 1, inner.top - 2)
        var y = inner.top - 2
        while (y <= inner.bottom + 2) {
            val x = edge + amp * sin(y / dp(4) + ph).toFloat()
            liquid.lineTo(x, y)
            y += dp(1.5f)
        }
        liquid.lineTo(inner.left - 1, inner.bottom + 2)
        liquid.close()
        fillP.shader = LinearGradient(inner.left, 0f, inner.right, 0f, 0xFF1FAF4B.toInt(), GREEN, Shader.TileMode.CLAMP)
        c.drawPath(liquid, fillP)
        fillP.shader = null
        c.restore()
        bolt(c, body.centerX(), cy, bh * 0.75f, Color.WHITE)

        tSub.textSize = dp(12)
        val label = if (chargeFull) "Fully charged" else "Charging"
        tRight.color = GREY
        tRight.textSize = dp(12)
        c.drawText(label, r.right - dp(18), cy - dp(8), tRight)
        tRight.color = GREEN
        tRight.textSize = dp(24)
        c.drawText("${(level * k).toInt()}%", r.right - dp(18), cy + dp(16), tRight)
    }

    // ---- notification + reply ----------------------------------------------------------------

    private fun drawNotif(c: Canvas, r: RectF) {
        val n = notif ?: return
        val band = compactH
        val size = dp(42)
        val il = r.left + dp(16)
        val it = band - dp(14) + dp(6)
        drawIcon(c, n.icon, il, it, size)
        val x = il + size + dp(12)
        val right = r.right - dp(18)
        tSub.textSize = dp(11.5f)
        text(c, n.appLabel, x, band * 0.5f + dp(4), tSub, max(dp(40), r.centerX() - dp(22) - x))
        tRight.color = GREY
        tRight.textSize = dp(11.5f)
        c.drawText("now", right, band * 0.5f + dp(4), tRight)
        tMain.textSize = dp(15)
        text(c, n.title, x, band + dp(16), tMain, right - x)
        tSub.textSize = dp(13.5f)
        if (n.text.isNotEmpty()) text(c, n.text, x, band + dp(35), tSub, right - x)

        if (n.otp != null || n.reply != null) {
            var bx = x
            val by = band + dp(48)
            if (n.otp != null) {
                val copied = SystemClock.uptimeMillis() - copiedAt < 2500
                bx += button(c, bx, by, if (copied) "Copied ✓" else "Copy ${n.otp}", if (copied) BTN_GREEN else BTN_BLUE, Act.COPY) + dp(8)
            }
            if (n.reply != null && bx + buttonWidth("Reply") <= right) button(c, bx, by, "Reply", BTN_GREY, Act.REPLY)
        }
    }

    private fun drawReply(c: Canvas, r: RectF) {
        val n = replying ?: return
        val top = compactH
        val il = r.left + dp(16)
        drawIcon(c, n.icon, il, top - dp(2), dp(30))
        val x = il + dp(40)
        val right = r.right - dp(18)
        tMain.textSize = dp(14.5f)
        text(c, n.title, x, top + dp(10), tMain, right - x)
        tSub.textSize = dp(12.5f)
        text(c, n.text, x, top + dp(28), tSub, right - x)
        // Text field background (the EditText sits on top of it) + send button.
        fillP.color = 0xFF1E1E1E.toInt()
        tmp.set(r.left + dp(16), top + dp(44), r.right - dp(60), top + dp(84))
        c.drawRoundRect(tmp, dp(20), dp(20), fillP)
        val sx = r.right - dp(36)
        val sy = top + dp(64)
        fillP.color = BLUE
        c.drawCircle(sx, sy, dp(19), fillP)
        strokeP.color = Color.BLACK
        strokeP.strokeWidth = dp(2.4f)
        c.drawLine(sx, sy + dp(7), sx, sy - dp(7), strokeP)
        c.drawLine(sx - dp(6), sy - dp(1), sx, sy - dp(7), strokeP)
        c.drawLine(sx + dp(6), sy - dp(1), sx, sy - dp(7), strokeP)
        if (recording) hits.add(Hit(RectF(sx - dp(24), sy - dp(24), sx + dp(24), sy + dp(24)), Act.SEND))
    }

    // ---- call card ------------------------------------------------------------------------------

    private fun drawCallCard(c: Canvas, r: RectF) {
        val call = LiveHub.call ?: return
        val top = compactH
        val cy = top + dp(16)
        val icx = r.left + dp(38)
        fillP.color = GREEN
        c.drawCircle(icx, cy, dp(18), fillP)
        phone(c, icx, cy, dp(18), Color.BLACK, 1f)
        val x0 = r.left + dp(66)
        val right = r.right - dp(16)
        tMain.textSize = dp(17)
        text(c, call.name, x0, cy - dp(1), tMain, right - x0)
        tSub.textSize = dp(13)
        tSub.color = if (call.incoming) GREY else GREEN
        c.drawText(if (call.incoming) "Incoming call" else clock(System.currentTimeMillis() - call.startedAt), x0, cy + dp(17), tSub)
        tSub.color = GREY
        var bx = x0
        val by = top + dp(44)
        if (call.answer != null) bx += button(c, bx, by, "Answer", BTN_GREEN, Act.CALL_ANSWER) + dp(8)
        if (call.speaker != null && !call.incoming) bx += button(c, bx, by, "Speaker", BTN_GREY, Act.CALL_SPEAKER) + dp(8)
        if (call.end != null) button(c, bx, by, if (call.incoming) "Decline" else "End", BTN_RED, Act.CALL_END)
        if (call.answer == null && call.speaker == null && call.end == null) {
            tSub.textSize = dp(12)
            c.drawText("Tap to open the call", x0, by + dp(20), tSub)
        }
    }

    // ---- pages -------------------------------------------------------------------------------

    private fun drawLivePage(c: Canvas, r: RectF) {
        val item = LiveHub.top() ?: return
        val top = compactH
        drawIcon(c, item.icon, r.left + dp(18), top + dp(2), dp(40))
        val x0 = r.left + dp(70)
        val right = r.right - dp(18)
        tSub.textSize = dp(11)
        text(c, item.appLabel.uppercase(), x0, top + dp(4), tSub, right - x0)
        tMain.textSize = dp(16)
        text(c, item.title, x0, top + dp(24), tMain, right - x0)
        tSub.textSize = dp(13)
        if (item.text.isNotEmpty()) text(c, item.text, x0, top + dp(43), tSub, right - x0)
        if (!item.nav) {
            val by = top + dp(58)
            if (item.indeterminate) {
                spinner(c, r.left + dp(38), by + dp(3), dp(8), BLUE)
                tSub.textSize = dp(12)
                c.drawText("Working…", x0, by + dp(7), tSub)
            } else {
                bar(c, x0, by, right - dp(44), dp(6), item.frac, BLUE)
                tRight.color = Color.WHITE
                tRight.textSize = dp(12.5f)
                c.drawText("${(item.frac * 100).toInt()}%", right, by + dp(6), tRight)
            }
        }
    }

    private fun drawMediaPage(c: Canvas, r: RectF) {
        val t = MediaHub.current ?: return
        val top = compactH - dp(4)
        val s = dp(58)
        art.set(r.left + dp(18), top + dp(2), r.left + dp(18) + s, top + dp(2) + s)
        drawArt(c, t.art, art, dp(12))
        val x0 = art.right + dp(14)
        val right = r.right - dp(18)
        eq(c, right, top + dp(16), dp(15), t.artColor, t.playing)
        tMain.textSize = dp(16)
        text(c, t.title, x0, top + dp(24), tMain, right - dp(28) - x0)
        tSub.textSize = dp(13.5f)
        text(c, t.artist, x0, top + dp(45), tSub, right - x0)

        val py = top + dp(80)
        if (t.durationMs > 0) {
            val pos = t.position()
            tSub.textSize = dp(11)
            c.drawText(clock(pos), r.left + dp(18), py + dp(4), tSub)
            tRight.color = GREY
            tRight.textSize = dp(11)
            c.drawText("-" + clock(t.durationMs - pos), right, py + dp(4), tRight)
            bar(c, r.left + dp(60), py - dp(2.5f), right - dp(46), dp(5), pos.toFloat() / t.durationMs, Color.WHITE)
        }
        val cy = top + dp(110)
        val mid = r.centerX()
        iconHit(c, mid - dp(72), cy, Act.PREV) { x, y -> skip(c, x, y, dp(22), forward = false) }
        iconHit(c, mid, cy, Act.PLAY_PAUSE) { x, y -> playPause(c, x, y, dp(26), t.playing) }
        iconHit(c, mid + dp(72), cy, Act.NEXT) { x, y -> skip(c, x, y, dp(22), forward = true) }
    }

    /** Pet page: big pet, litres + pace marker, streak, quick-add chips and drink type. */
    private fun drawDripPage(c: Canvas, r: RectF) {
        val top = compactH
        val ml = Water.todayMl(app)
        val goal = Water.goal(prefs)
        val pcx = r.left + dp(58)
        val pcy = top + dp(40)
        val lk = look(pcx, pcy)
        PetEngine.draw(c, app, pcx, pcy, dp(86), lookX = lk[0], lookY = lk[1], dancing = dancing())
        if (recording) hits.add(Hit(RectF(pcx - dp(43), pcy - dp(43), pcx + dp(43), pcy + dp(43)), Act.POKE))
        val x0 = r.left + dp(112)
        val right = r.right - dp(18)
        tSub.textSize = dp(11)
        text(c, PetEngine.stageName(app).uppercase(), x0, top + dp(4), tSub, right - x0 - dp(50))
        tRight.color = ORANGE
        tRight.textSize = dp(12)
        c.drawText("🔥 ${Water.streak(app)}", right, top + dp(4), tRight)
        tMain.textSize = dp(24)
        val big = Water.liters(ml)
        c.drawText(big, x0, top + dp(31), tMain)
        val bw = tMain.measureText(big)
        tSub.textSize = dp(13)
        c.drawText(" / ${Water.liters(goal)} L", x0 + bw + dp(2), top + dp(31), tSub)
        bar(c, x0, top + dp(40), right, dp(6), waterFrac(), if (ml >= goal) GREEN else BLUE)
        // Pace marker: where you "should" be by now.
        val pace = (Water.expected(app).toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
        val mx = x0 + (right - x0) * pace
        c.drawLine(mx, top + dp(37), mx, top + dp(49), strokeP.apply { color = Color.WHITE; strokeWidth = dp(2) })
        tSub.textSize = dp(12)
        if (PetEngine.pokeShowing()) {
            tMain.textSize = dp(13)
            text(c, "“${PetEngine.pokeLine}”", x0, top + dp(64), tMain, right - x0)
        } else {
            text(c, Water.paceLine(app), x0, top + dp(64), tSub, right - x0)
        }
        // Quick add + drink type
        val by = top + dp(92)
        var bx = r.left + dp(16)
        val cups = Water.cups(prefs)
        for ((i, act) in listOf(Act.ADD100, Act.ADD250, Act.ADD500).withIndex()) {
            val ml2 = cups.getOrNull(i)?.second ?: continue
            bx += button(c, bx, by, "+$ml2", if (ml2 == Water.cup(prefs)) BTN_BLUE else BTN_GREY, act) + dp(6)
        }
        val t = Water.drinkType(prefs)
        val label = "${t.emoji} ${t.label}"
        if (bx + buttonWidth(label) <= right) button(c, bx, by, label, BTN_GREY, Act.CYCLE_TYPE)
    }

    private fun drawTimerPage(c: Canvas, r: RectF) {
        val top = compactH
        val right = r.right - dp(16)
        if (!IslandTimer.active(app)) {
            tMain.textSize = dp(17)
            c.drawText("Focus timer", r.left + dp(18), top + dp(16), tMain)
            tSub.textSize = dp(12.5f)
            text(c, IslandTimer.statsLine(app), r.left + dp(18), top + dp(36), tSub, right - r.left - dp(18))
            var bx = r.left + dp(16)
            for ((m, act) in listOf(5 to Act.T5, 15 to Act.T15, 25 to Act.T25, 45 to Act.T45)) {
                val label = "${m} min"
                if (bx + buttonWidth(label) > right) break
                bx += button(c, bx, top + dp(50), label, if (m == 25) BTN_ORANGE else BTN_GREY, act) + dp(6)
            }
            return
        }
        val rem = IslandTimer.remainingMs(app)
        val total = IslandTimer.totalMs(prefs).coerceAtLeast(1)
        val paused = IslandTimer.paused(app)
        val cy = top + dp(20)
        ring(c, r.left + dp(38), cy, dp(16), dp(4), 0xFF3A2A12.toInt(), if (paused) 0xFFB07A2A.toInt() else ORANGE, rem.toFloat() / total)
        tMain.textSize = dp(32)
        tMain.color = if (paused) 0xFFB07A2A.toInt() else ORANGE
        c.drawText(IslandTimer.format(rem), r.left + dp(66), cy + dp(11.5f), tMain)
        tMain.color = Color.WHITE

        val ccx = r.right - dp(36)
        fillP.color = 0xFF3A1A17.toInt()
        c.drawCircle(ccx, cy, dp(17), fillP)
        strokeP.color = RED
        strokeP.strokeWidth = dp(2.4f)
        val k = dp(5.5f)
        c.drawLine(ccx - k, cy - k, ccx + k, cy + k, strokeP)
        c.drawLine(ccx - k, cy + k, ccx + k, cy - k, strokeP)
        if (recording) hits.add(Hit(RectF(ccx - dp(24), cy - dp(24), ccx + dp(24), cy + dp(24)), Act.T_CANCEL))

        val by = top + dp(50)
        var bx = r.left + dp(16)
        bx += button(c, bx, by, if (paused) "Resume" else "Pause", if (paused) BTN_ORANGE else BTN_GREY,
            if (paused) Act.T_RESUME else Act.T_PAUSE) + dp(6)
        bx += button(c, bx, by, "+1 min", BTN_GREY, Act.T_ADD1) + dp(10)
        tRight.color = GREY
        tRight.textSize = dp(11.5f)
        val stats = IslandTimer.statsLine(app).removePrefix("Today · ")
        if (right - bx > tRight.measureText(stats)) c.drawText(stats, right, by + dp(19), tRight)
    }

    // ---- other peeks ---------------------------------------------------------------------------

    private fun drawTimerDone(c: Canvas, r: RectF) {
        val top = compactH
        val cy = top + dp(22)
        val k = 0.5f + 0.5f * sin(SystemClock.uptimeMillis() / 180.0).toFloat()
        val icx = r.left + dp(38)
        fillP.color = ORANGE
        fillP.alpha = (50 + 50 * k).toInt()
        c.drawCircle(icx, cy, dp(18) + dp(4) * k, fillP)
        fillP.alpha = 255
        c.drawCircle(icx, cy, dp(15), fillP)
        strokeP.color = Color.BLACK
        strokeP.strokeWidth = dp(2.2f)
        c.drawCircle(icx, cy + dp(1), dp(7.5f), strokeP)
        c.drawLine(icx, cy + dp(1), icx, cy - dp(3.5f), strokeP)
        c.drawLine(icx, cy + dp(1), icx + dp(3), cy + dp(2.5f), strokeP)

        val okW = buttonWidth("OK")
        val addW = buttonWidth("+5 min")
        val right = r.right - dp(16)
        val bx = right - okW - dp(8) - addW
        button(c, bx, cy - dp(15), "+5 min", BTN_GREY, Act.T_ADD5)
        button(c, right - okW, cy - dp(15), "OK", BTN_ORANGE, Act.T_DISMISS)
        val x0 = r.left + dp(66)
        tMain.textSize = dp(17)
        text(c, "Timer done", x0, cy - dp(2), tMain, bx - dp(8) - x0)
        tSub.textSize = dp(12.5f)
        text(c, IslandTimer.statsLine(app), x0, cy + dp(16), tSub, bx - dp(8) - x0)
    }

    private fun drawThirsty(c: Canvas, r: RectF) {
        val top = compactH
        val pcx = r.left + dp(50)
        val pcy = top + dp(34)
        val lk = look(pcx, pcy)
        PetEngine.draw(c, app, pcx, pcy, dp(70), lookX = lk[0], lookY = lk[1])
        val x0 = r.left + dp(96)
        val right = r.right - dp(16)
        tMain.textSize = dp(17)
        text(c, thirstyLine, x0, top + dp(16), tMain, right - x0)
        tSub.textSize = dp(12)
        val mins = Water.minutesSinceDrink(app)
        val since = if (mins < 0) "No drinks yet" else "Last sip $mins min ago"
        text(c, "$since · ${Water.paceLine(app)}", x0, top + dp(35), tSub, right - x0)
        val by = top + dp(50)
        var bx = x0
        bx += button(c, bx, by, "Drink ${Water.cup(prefs)} ml", BTN_BLUE, Act.DRINK) + dp(8)
        val snooze = if (bx + buttonWidth("Snooze 10 min") <= right) "Snooze 10 min" else "Snooze"
        button(c, bx, by, snooze, BTN_GREY, Act.SNOOZE)
    }

    private fun drawSplash(c: Canvas, r: RectF) {
        val top = compactH
        val ml = Water.todayMl(app)
        val goal = Water.goal(prefs)
        PetEngine.draw(c, app, r.left + dp(50), top + dp(28), dp(64))
        val x0 = r.left + dp(96)
        val right = r.right - dp(18)
        val undoW = buttonWidth("Undo")
        tMain.textSize = dp(18)
        tMain.color = BLUE
        text(c, lastDrinkLabel, x0, top + dp(12), tMain, right - undoW - dp(8) - x0)
        tMain.color = Color.WHITE
        button(c, right - undoW, top - dp(8), "Undo", BTN_GREY, Act.UNDO)
        tSub.textSize = dp(12)
        text(c, Water.paceLine(app), x0, top + dp(32), tSub, right - x0)
        bar(c, x0, top + dp(42), right, dp(6), waterFrac(), if (ml >= goal) GREEN else BLUE)
        tSub.textSize = dp(11)
        c.drawText("${Water.liters(ml)} / ${Water.liters(goal)} L today", x0, top + dp(62), tSub)
    }

    /** Goal reached (hearts) or the pet evolved into its next stage. */
    private fun drawCelebration(c: Canvas, r: RectF, evolved: Boolean) {
        val top = compactH
        PetEngine.draw(c, app, r.left + dp(52), top + dp(36), dp(76))
        val x0 = r.left + dp(102)
        val right = r.right - dp(16)
        tMain.textSize = dp(17)
        tMain.color = if (evolved) 0xFFFFD54F.toInt() else GREEN
        text(c, if (evolved) "Evolved into a ${PetEngine.stageName(app)}!" else "Goal reached! 🎉", x0, top + dp(18), tMain, right - x0)
        tMain.color = Color.WHITE
        tSub.textSize = dp(12.5f)
        val b = Water.blooms(app)
        val next = Water.bloomsToNext(app)
        text(c, "Bloom day #$b · 🔥 ${Water.streak(app)}-day streak", x0, top + dp(38), tSub, right - x0)
        tSub.textSize = dp(11.5f)
        text(c, if (next < 0) "Final stage — fully grown" else "$next more bloom${if (next == 1) "" else "s"} to evolve", x0, top + dp(56), tSub, right - x0)
    }

    // ---- primitives -------------------------------------------------------------------------

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun text(c: Canvas, s: String, x: Float, baseline: Float, p: TextPaint, maxW: Float) {
        if (maxW <= 0f || s.isEmpty()) return
        val e = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END)
        c.drawText(e, 0, e.length, x, baseline, p)
    }

    private fun buttonWidth(label: String): Float {
        tBtn.textSize = dp(13)
        return tBtn.measureText(label) + dp(26)
    }

    private fun button(c: Canvas, x: Float, y: Float, label: String, style: Int, act: Act): Float {
        val h = dp(30)
        val w = buttonWidth(label)
        tmp.set(x, y, x + w, y + h)
        fillP.color = when (style) {
            BTN_BLUE -> BLUE
            BTN_ORANGE -> ORANGE
            BTN_GREEN -> GREEN
            BTN_RED -> RED
            else -> 0xFF2A2A2A.toInt()
        }
        c.drawRoundRect(tmp, h / 2f, h / 2f, fillP)
        tBtn.color = if (style == BTN_GREY || style == BTN_RED) Color.WHITE else Color.BLACK
        c.drawText(label, tmp.centerX(), tmp.centerY() - (tBtn.ascent() + tBtn.descent()) / 2f, tBtn)
        if (recording) hits.add(Hit(RectF(x - dp(4), y - dp(6), x + w + dp(4), y + h + dp(6)), act))
        return w
    }

    private inline fun iconHit(c: Canvas, x: Float, y: Float, act: Act, draw: (Float, Float) -> Unit) {
        draw(x, y)
        if (recording) hits.add(Hit(RectF(x - dp(28), y - dp(24), x + dp(28), y + dp(24)), act))
    }

    private fun drawIcon(c: Canvas, ic: Drawable?, l: Float, t: Float, size: Float) {
        if (ic == null) {
            fillP.color = 0xFF2A2A2A.toInt()
            c.drawCircle(l + size / 2f, t + size / 2f, size / 2f, fillP)
            return
        }
        c.save()
        artClip.reset()
        artClip.addCircle(l + size / 2f, t + size / 2f, size / 2f, Path.Direction.CW)
        c.clipPath(artClip)
        ic.setBounds(l.toInt(), t.toInt(), (l + size).toInt(), (t + size).toInt())
        ic.draw(c)
        c.restore()
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, sw: Float, track: Int, color: Int, frac: Float, start: Float = -90f) {
        strokeP.strokeWidth = sw
        strokeP.color = track
        c.drawCircle(cx, cy, r, strokeP)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.005f) {
            strokeP.color = color
            tmp.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(tmp, start, 360f * f, false, strokeP)
        }
    }

    private fun spinner(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        strokeP.strokeWidth = dp(2.6f)
        strokeP.color = color
        tmp.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(tmp, (SystemClock.uptimeMillis() / 3L % 360L).toFloat(), 270f, false, strokeP)
    }

    private fun bar(c: Canvas, l: Float, t: Float, r: Float, h: Float, frac: Float, color: Int) {
        if (r <= l) return
        fillP.color = 0xFF2A2A2A.toInt()
        tmp.set(l, t, r, t + h)
        c.drawRoundRect(tmp, h / 2f, h / 2f, fillP)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            fillP.color = color
            tmp.set(l, t, l + max(h, (r - l) * f), t + h)
            c.drawRoundRect(tmp, h / 2f, h / 2f, fillP)
        }
    }

    private fun drawArt(c: Canvas, b: Bitmap?, rect: RectF, radius: Float) {
        if (b != null && !b.isRecycled) {
            c.save()
            artClip.reset()
            artClip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            c.clipPath(artClip)
            c.drawBitmap(b, null, rect, bmpP)
            c.restore()
        } else {
            fillP.color = 0xFF2A2A2A.toInt()
            c.drawRoundRect(rect, radius, radius, fillP)
            tBtn.color = Color.WHITE
            tBtn.textSize = rect.height() * 0.55f
            c.drawText("♪", rect.centerX(), rect.centerY() - (tBtn.ascent() + tBtn.descent()) / 2f, tBtn)
        }
    }

    /** Four little bars; they dance while music plays. [right] is the right edge. */
    private fun eq(c: Canvas, right: Float, cy: Float, maxH: Float, color: Int, playing: Boolean) {
        val bw = dp(2.8f)
        val gap = dp(2.4f)
        val t = SystemClock.uptimeMillis() / 1000.0
        fillP.color = color
        for (i in 0 until 4) {
            val k = if (playing) {
                val a = abs(sin(t * EQ_F[i] + EQ_P[i])).toFloat()
                val b = abs(sin(t * EQ_F[3 - i] * 0.6 + EQ_P[i] * 2)).toFloat()
                0.25f + 0.75f * (a * 0.6f + b * 0.4f)
            } else 0.2f
            val h = max(bw, maxH * k)
            val x = right - (4 - i) * bw - (3 - i) * gap
            tmp.set(x, cy - h / 2f, x + bw, cy + h / 2f)
            c.drawRoundRect(tmp, bw / 2f, bw / 2f, fillP)
        }
    }

    private fun playPause(c: Canvas, cx: Float, cy: Float, s: Float, playing: Boolean) {
        fillP.color = Color.WHITE
        if (playing) {
            val w = s * 0.26f
            tmp.set(cx - s * 0.30f, cy - s * 0.42f, cx - s * 0.30f + w, cy + s * 0.42f)
            c.drawRoundRect(tmp, w / 3f, w / 3f, fillP)
            tmp.set(cx + s * 0.30f - w, cy - s * 0.42f, cx + s * 0.30f, cy + s * 0.42f)
            c.drawRoundRect(tmp, w / 3f, w / 3f, fillP)
        } else {
            icon.reset()
            icon.moveTo(cx - s * 0.32f, cy - s * 0.45f)
            icon.lineTo(cx + s * 0.48f, cy)
            icon.lineTo(cx - s * 0.32f, cy + s * 0.45f)
            icon.close()
            c.drawPath(icon, fillP)
        }
    }

    private fun skip(c: Canvas, cx: Float, cy: Float, s: Float, forward: Boolean) {
        fillP.color = Color.WHITE
        val dir = if (forward) 1f else -1f
        icon.reset()
        for (k in 0..1) {
            val x0 = cx + dir * (-s * 0.5f + k * s * 0.5f)
            icon.moveTo(x0, cy - s * 0.32f)
            icon.lineTo(x0 + dir * s * 0.5f, cy)
            icon.lineTo(x0, cy + s * 0.32f)
            icon.close()
        }
        c.drawPath(icon, fillP)
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        fillP.color = color
        icon.reset()
        icon.moveTo(cx + s * 0.10f, cy - s * 0.50f)
        icon.lineTo(cx - s * 0.28f, cy + s * 0.08f)
        icon.lineTo(cx - s * 0.02f, cy + s * 0.08f)
        icon.lineTo(cx - s * 0.12f, cy + s * 0.50f)
        icon.lineTo(cx + s * 0.28f, cy - s * 0.10f)
        icon.lineTo(cx + s * 0.02f, cy - s * 0.10f)
        icon.close()
        c.drawPath(icon, fillP)
    }

    /** A handset: thick "C" arc with round ends, tilted. */
    private fun phone(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
        c.save()
        c.rotate(-30f, cx, cy)
        strokeP.color = color
        strokeP.alpha = (255 * alpha).toInt()
        strokeP.strokeWidth = s * 0.24f
        tmp.set(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f)
        c.drawArc(tmp, 120f, 120f, false, strokeP)
        fillP.color = color
        fillP.alpha = (255 * alpha).toInt()
        for (deg in floatArrayOf(120f, 240f)) {
            val a = Math.toRadians(deg.toDouble())
            c.drawCircle(cx + (s * 0.36f * kotlin.math.cos(a)).toFloat(), cy + (s * 0.36f * sin(a)).toFloat(), s * 0.17f, fillP)
        }
        fillP.alpha = 255
        strokeP.alpha = 255
        c.restore()
    }

    private fun speaker(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, level: Float) {
        fillP.color = color
        icon.reset()
        icon.moveTo(cx - s * 0.5f, cy - s * 0.18f)
        icon.lineTo(cx - s * 0.22f, cy - s * 0.18f)
        icon.lineTo(cx + s * 0.08f, cy - s * 0.45f)
        icon.lineTo(cx + s * 0.08f, cy + s * 0.45f)
        icon.lineTo(cx - s * 0.22f, cy + s * 0.18f)
        icon.lineTo(cx - s * 0.5f, cy + s * 0.18f)
        icon.close()
        c.drawPath(icon, fillP)
        strokeP.color = color
        strokeP.strokeWidth = s * 0.1f
        if (level > 0.01f) {
            tmp.set(cx - s * 0.1f, cy - s * 0.25f, cx + s * 0.4f, cy + s * 0.25f)
            c.drawArc(tmp, -50f, 100f, false, strokeP)
        }
        if (level > 0.5f) {
            tmp.set(cx - s * 0.2f, cy - s * 0.45f, cx + s * 0.6f, cy + s * 0.45f)
            c.drawArc(tmp, -50f, 100f, false, strokeP)
        }
        if (level <= 0.01f) {
            c.drawLine(cx + s * 0.22f, cy - s * 0.15f, cx + s * 0.5f, cy + s * 0.15f, strokeP)
            c.drawLine(cx + s * 0.22f, cy + s * 0.15f, cx + s * 0.5f, cy - s * 0.15f, strokeP)
        }
    }

    private fun bell(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        fillP.color = color
        icon.reset()
        icon.moveTo(cx - s * 0.42f, cy + s * 0.25f)
        icon.cubicTo(cx - s * 0.30f, cy + s * 0.10f, cx - s * 0.32f, cy - s * 0.42f, cx, cy - s * 0.42f)
        icon.cubicTo(cx + s * 0.32f, cy - s * 0.42f, cx + s * 0.30f, cy + s * 0.10f, cx + s * 0.42f, cy + s * 0.25f)
        icon.close()
        c.drawPath(icon, fillP)
        c.drawCircle(cx, cy + s * 0.36f, s * 0.09f, fillP)
    }

    private fun glyph(c: Canvas, g: Glyph, cx: Float, cy: Float, s: Float, color: Int) {
        when (g) {
            Glyph.CAMERA, Glyph.MIC -> {
                fillP.color = color
                c.drawCircle(cx, cy, s * 0.22f, fillP)
            }
            Glyph.BELL -> bell(c, cx, cy, s, color)
            Glyph.BELL_OFF -> {
                fillP.color = RED
                c.drawCircle(cx, cy, s * 0.62f, fillP)
                bell(c, cx, cy, s * 0.8f, Color.WHITE)
                strokeP.color = Color.WHITE
                strokeP.strokeWidth = s * 0.1f
                c.drawLine(cx - s * 0.38f, cy - s * 0.38f, cx + s * 0.38f, cy + s * 0.38f, strokeP)
            }
            Glyph.VIBRATE -> {
                strokeP.color = color
                strokeP.strokeWidth = s * 0.1f
                tmp.set(cx - s * 0.2f, cy - s * 0.38f, cx + s * 0.2f, cy + s * 0.38f)
                c.drawRoundRect(tmp, s * 0.08f, s * 0.08f, strokeP)
                for (side in floatArrayOf(-1f, 1f)) {
                    c.drawLine(cx + side * s * 0.34f, cy - s * 0.2f, cx + side * s * 0.34f, cy + s * 0.2f, strokeP)
                    c.drawLine(cx + side * s * 0.48f, cy - s * 0.1f, cx + side * s * 0.48f, cy + s * 0.1f, strokeP)
                }
            }
            Glyph.HEADPHONES -> {
                strokeP.color = color
                strokeP.strokeWidth = s * 0.12f
                tmp.set(cx - s * 0.4f, cy - s * 0.42f, cx + s * 0.4f, cy + s * 0.38f)
                c.drawArc(tmp, 180f, 180f, false, strokeP)
                fillP.color = color
                tmp.set(cx - s * 0.48f, cy - s * 0.02f, cx - s * 0.24f, cy + s * 0.42f)
                c.drawRoundRect(tmp, s * 0.08f, s * 0.08f, fillP)
                tmp.set(cx + s * 0.24f, cy - s * 0.02f, cx + s * 0.48f, cy + s * 0.42f)
                c.drawRoundRect(tmp, s * 0.08f, s * 0.08f, fillP)
            }
            Glyph.WATCH -> {
                strokeP.color = color
                strokeP.strokeWidth = s * 0.1f
                tmp.set(cx - s * 0.26f, cy - s * 0.3f, cx + s * 0.26f, cy + s * 0.3f)
                c.drawRoundRect(tmp, s * 0.1f, s * 0.1f, strokeP)
                c.drawLine(cx - s * 0.14f, cy - s * 0.3f, cx - s * 0.14f, cy - s * 0.5f, strokeP)
                c.drawLine(cx + s * 0.14f, cy - s * 0.3f, cx + s * 0.14f, cy - s * 0.5f, strokeP)
                c.drawLine(cx - s * 0.14f, cy + s * 0.3f, cx - s * 0.14f, cy + s * 0.5f, strokeP)
                c.drawLine(cx + s * 0.14f, cy + s * 0.3f, cx + s * 0.14f, cy + s * 0.5f, strokeP)
            }
            Glyph.BLUETOOTH -> {
                strokeP.color = color
                strokeP.strokeWidth = s * 0.1f
                icon.reset()
                icon.moveTo(cx - s * 0.25f, cy - s * 0.22f)
                icon.lineTo(cx + s * 0.22f, cy + s * 0.2f)
                icon.lineTo(cx, cy + s * 0.42f)
                icon.lineTo(cx, cy - s * 0.42f)
                icon.lineTo(cx + s * 0.22f, cy - s * 0.2f)
                icon.lineTo(cx - s * 0.25f, cy + s * 0.22f)
                strokeP.style = Paint.Style.STROKE
                c.drawPath(icon, strokeP)
            }
            Glyph.CHECK -> {
                strokeP.color = color
                strokeP.strokeWidth = s * 0.14f
                c.drawLine(cx - s * 0.32f, cy, cx - s * 0.08f, cy + s * 0.24f, strokeP)
                c.drawLine(cx - s * 0.08f, cy + s * 0.24f, cx + s * 0.36f, cy - s * 0.26f, strokeP)
            }
            Glyph.CROSS -> {
                strokeP.color = color
                strokeP.strokeWidth = s * 0.14f
                c.drawLine(cx - s * 0.28f, cy - s * 0.28f, cx + s * 0.28f, cy + s * 0.28f, strokeP)
                c.drawLine(cx - s * 0.28f, cy + s * 0.28f, cx + s * 0.28f, cy - s * 0.28f, strokeP)
            }
            Glyph.STAR -> {
                fillP.color = color
                icon.reset()
                for (i in 0 until 10) {
                    val rad = if (i % 2 == 0) s * 0.5f else s * 0.22f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = cx + (kotlin.math.cos(a) * rad).toFloat()
                    val y = cy + (sin(a) * rad).toFloat()
                    if (i == 0) icon.moveTo(x, y) else icon.lineTo(x, y)
                }
                icon.close()
                c.drawPath(icon, fillP)
            }
        }
    }

    private fun batteryGlyph(c: Canvas, x: Float, cy: Float, w: Float, h: Float, frac: Float, color: Int) {
        strokeP.color = 0xFF8A8A8A.toInt()
        strokeP.strokeWidth = dp(1.3f)
        tmp.set(x, cy - h / 2f, x + w - dp(3), cy + h / 2f)
        c.drawRoundRect(tmp, dp(3.5f), dp(3.5f), strokeP)
        fillP.color = 0xFF8A8A8A.toInt()
        tmp.set(x + w - dp(2), cy - h * 0.2f, x + w, cy + h * 0.2f)
        c.drawRoundRect(tmp, dp(1), dp(1), fillP)
        val inner = w - dp(3) - dp(4)
        fillP.color = color
        tmp.set(x + dp(2), cy - h / 2f + dp(2), x + dp(2) + inner * frac.coerceIn(0.04f, 1f), cy + h / 2f - dp(2))
        c.drawRoundRect(tmp, dp(2), dp(2), fillP)
    }

    // =====================================================================================
    // Touch
    // =====================================================================================

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longFired = false
    private val longPress = Runnable {
        if (peek == Peek.REPLY) return@Runnable
        longFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        collapse()
        host.openApp()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                onOutsideTouch()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                touchX = e.x
                touchY = e.y
                touchAt = SystemClock.uptimeMillis()
                moved = false
                longFired = false
                shadeFired = false
                downOnShape = onShape(e.x, e.y)
                if (downOnShape) {
                    postDelayed(longPress, 550)
                    extendTimeouts()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = e.x
                touchY = e.y
                touchAt = SystemClock.uptimeMillis()
                val dx = e.x - downX
                val dy = e.y - downY
                // A pull down that starts on the island is the user reaching for the notification
                // shade: hand it to the system straight away, like the real status-bar gesture.
                if (!shadeFired && !longFired && peek != Peek.REPLY && dy > dp(12) && dy > abs(dx)) {
                    shadeFired = true
                    moved = true
                    removeCallbacks(longPress)
                    if (peek != Peek.NONE || expanded) collapse()
                    host.openShade()
                    return true
                }
                if (!moved && hypot(dx, dy) > dp(12)) {
                    moved = true
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longFired || shadeFired) return true
                val dx = e.x - downX
                val dy = e.y - downY
                if (moved) {
                    if (abs(dx) > abs(dy) && abs(dx) > dp(36)) { if (downOnShape) onSwipe(if (dx < 0) 1 else -1) }
                    else if (dy < -dp(20)) { if (peek == Peek.REPLY) cancelReply() else collapse() }
                } else {
                    onTap(e.x, e.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    /** dir = +1 for a left swipe (next), -1 for a right swipe (previous). */
    private fun onSwipe(dir: Int) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (layout) {
            Layout.MEDIA_C -> if (dir > 0) MediaHub.next() else MediaHub.prev()
            // In the expanded island a sideways swipe always flips pages (skip tracks with the buttons).
            Layout.PAGE_MEDIA, Layout.PAGE_LIVE, Layout.PAGE_DRIP, Layout.PAGE_TIMER -> goPage(dir)
            Layout.NOTIF, Layout.INFO, Layout.VOLUME, Layout.CHARGE -> collapse()
            else -> {}
        }
    }

    private fun onTap(x: Float, y: Float) {
        if (!onShape(x, y)) {
            // Transparent part of the (constant-width) window: nothing to tap here. Only an open
            // peek / expanded card closes, like a tap anywhere else outside it.
            if (peek != Peek.NONE || expanded) onOutsideTouch()
            return
        }
        if (layout == Layout.DOCK) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setPeek(Peek.IDLE, 3000)
            return
        }
        if (!pill.contains(x, y) && (peek != Peek.NONE || expanded)) {
            onOutsideTouch()
            return
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        for (h in hits) {
            if (h.r.contains(x, y)) {
                perform(h.act, h.arg)
                return
            }
        }
        when (layout) {
            Layout.IDLE_C, Layout.MEDIA_C, Layout.TIMER_C, Layout.LIVE_C, Layout.CALL_C -> expand()
            Layout.NOTIF -> {
                val pi = notif?.intent
                collapse()
                host.send(pi)
            }
            Layout.PAGE_MEDIA -> {
                val pi = MediaHub.current?.sessionActivity
                collapse()
                host.send(pi)
            }
            Layout.PAGE_LIVE -> {
                val pi = LiveHub.top()?.open
                collapse()
                host.send(pi)
            }
            Layout.CALL_X -> {
                val pi = LiveHub.call?.open
                collapse()
                host.send(pi)
            }
            Layout.TIMER_DONE -> {
                IslandTimer.cancelDoneNotification(app)
                collapse()
            }
            Layout.THIRSTY, Layout.REPLY -> {}
            else -> collapse()
        }
    }

    private fun perform(act: Act, arg: Int) {
        when (act) {
            Act.PLAY_PAUSE -> MediaHub.playPause()
            Act.NEXT -> MediaHub.next()
            Act.PREV -> MediaHub.prev()
            Act.DRINK -> Water.drink(app) // the prefs change triggers the splash
            Act.UNDO -> {
                val e = Water.undo(app)
                if (layout == Layout.SPLASH) {
                    peek = Peek.NONE
                    if (e != null) showInfo(Glyph.CROSS, "Removed", "${e.ml} ml ${e.drink.label}", GREY, 1500) else refresh()
                }
            }
            Act.SNOOZE -> {
                Water.snooze(app)
                collapse()
            }
            Act.POKE -> PetEngine.poke()
            Act.ADD100 -> Water.drink(app, Water.cups(prefs).getOrNull(0)?.second ?: 100)
            Act.ADD250 -> Water.drink(app, Water.cups(prefs).getOrNull(1)?.second ?: 250)
            Act.ADD500 -> Water.drink(app, Water.cups(prefs).getOrNull(2)?.second ?: 500)
            Act.CYCLE_TYPE -> {
                val next = (Water.drinkType(prefs).ordinal + 1) % Drink.entries.size
                prefs.edit().putInt(Prefs.DRINK_TYPE, next).apply()
            }
            Act.T5 -> startTimer(5)
            Act.T15 -> startTimer(15)
            Act.T25 -> startTimer(25)
            Act.T45 -> startTimer(45)
            Act.T_ADD1 -> IslandTimer.add(app, 60_000)
            Act.T_PAUSE -> IslandTimer.pause(app)
            Act.T_RESUME -> IslandTimer.resume(app)
            Act.T_CANCEL -> {
                IslandTimer.cancel(app)
                collapse()
            }
            Act.T_ADD5 -> {
                IslandTimer.cancelDoneNotification(app)
                IslandTimer.start(app, 5)
                collapse()
            }
            Act.T_DISMISS -> {
                IslandTimer.cancelDoneNotification(app)
                collapse()
            }
            Act.PAGE -> {
                val ps = pages()
                val i = ps.indexOf(page)
                if (arg in ps.indices && arg != i) {
                    page = ps[arg]
                    pendingSlide = if (arg > i) 1 else -1
                    refresh()
                }
            }
            Act.COPY -> {
                val code = notif?.otp
                if (code != null) {
                    try {
                        app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Code", code))
                        copiedAt = SystemClock.uptimeMillis()
                        peekUntil = System.currentTimeMillis() + 2500
                        scheduleExpiry()
                    } catch (_: Exception) {
                    }
                }
            }
            Act.REPLY -> startReply()
            Act.SEND -> sendReply()
            Act.CALL_END -> {
                host.send(LiveHub.call?.end)
                collapse()
            }
            Act.CALL_SPEAKER -> host.send(LiveHub.call?.speaker)
            Act.CALL_ANSWER -> host.send(LiveHub.call?.answer)
        }
        extendTimeouts()
        invalidate()
    }

    private fun startTimer(min: Int) {
        IslandTimer.start(app, min)
        expanded = false
        refresh()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(expiry)
        removeCallbacks(tickR)
        removeCallbacks(longPress)
        removeCallbacks(showFieldR)
        removeCallbacks(replyFallback)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val K = 300f   // spring stiffness
        private const val C = 26f    // damping (a touch under critical -> small bounce)
        private const val SLIDE_MS = 260L
        const val BLUE = 0xFF4FC3F7.toInt()
        const val ORANGE = 0xFFFF9F0A.toInt()
        const val GREEN = 0xFF30D158.toInt()
        const val RED = 0xFFFF453A.toInt()
        const val GREY = 0xFF9A9A9A.toInt()
        private const val BTN_GREY = 0
        private const val BTN_BLUE = 1
        private const val BTN_ORANGE = 2
        private const val BTN_GREEN = 3
        private const val BTN_RED = 4
        private val EQ_F = doubleArrayOf(7.1, 9.3, 6.2, 8.4)
        private val EQ_P = doubleArrayOf(0.0, 1.3, 2.6, 0.7)
        private val ZERO2 = floatArrayOf(0f, 0f)
    }
}
