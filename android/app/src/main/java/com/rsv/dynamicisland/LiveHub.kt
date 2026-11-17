package com.rsv.dynamicisland

import android.app.PendingIntent
import android.graphics.drawable.Drawable

/** An ongoing phone call, read from the dialer's call notification. */
class CallInfo(
    val key: String,
    val name: String,
    /** Wall-clock start of the connected call, 0 while ringing. */
    val startedAt: Long,
    val incoming: Boolean,
    val end: PendingIntent?,
    val speaker: PendingIntent?,
    val answer: PendingIntent?,
    val open: PendingIntent?,
)

/** A "live activity": an ongoing notification with progress, or turn-by-turn navigation. */
class LiveItem(
    val key: String,
    val appLabel: String,
    val icon: Drawable?,
    val title: String,
    val text: String,
    val progress: Int,
    val max: Int,
    val indeterminate: Boolean,
    val nav: Boolean,
    val open: PendingIntent?,
    val updatedAt: Long,
) {
    val frac: Float get() = if (max > 0) (progress.toFloat() / max).coerceIn(0f, 1f) else 0f
}

/** Calls + live activities, fed by [NotifListener]. Main thread only. */
object LiveHub {
    var call: CallInfo? = null
        private set
    private val items = LinkedHashMap<String, LiveItem>()
    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun setCall(c: CallInfo?) {
        call = c
        notifyListeners()
    }

    fun put(item: LiveItem) {
        items[item.key] = item
        notifyListeners()
    }

    fun get(key: String): LiveItem? = items[key]

    fun remove(key: String) {
        var changed = false
        if (call?.key == key) {
            call = null
            changed = true
        }
        if (items.remove(key) != null) changed = true
        if (changed) notifyListeners()
    }

    fun clear() {
        call = null
        items.clear()
        notifyListeners()
    }

    fun nav(): LiveItem? = items.values.filter { it.nav }.maxByOrNull { it.updatedAt }
    fun progressItem(): LiveItem? = items.values.filter { !it.nav }.maxByOrNull { it.updatedAt }

    /** Navigation wins (it's time-critical), otherwise the most recent progress item. */
    fun top(): LiveItem? = nav() ?: progressItem()

    private fun notifyListeners() {
        for (l in listeners.toList()) l()
    }
}
