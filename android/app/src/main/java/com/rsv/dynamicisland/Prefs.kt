package com.rsv.dynamicisland

import android.content.Context
import android.content.SharedPreferences

/** Every setting and every bit of persisted state lives in one SharedPreferences file. */
object Prefs {
    private const val FILE = "island"

    // Island
    const val ISLAND_ON = "island_on"
    const val OFFSET_X = "offset_x_dp"
    const val OFFSET_Y = "offset_y_dp"
    /** Extra drop for the small pill only (so it clears the camera); the docked and full views don't move. */
    const val PILL_GAP = "pill_gap_dp"
    const val WIDTH = "width_dp"
    const val HEIGHT = "height_dp"
    const val AUTOFIT_DONE = "autofit_done"
    const val PEEK_NOTIFS = "peek_notifs"
    const val PEEK_CHARGING = "peek_charging"
    /** Default off: on the lock screen the island only shows while music / video plays; never on the always-on display. */
    const val LOCK_ALWAYS = "lock_always"
    /** Default on: the island's peek replaces the system pop-up (see [HeadsUp]). */
    const val REPLACE_POPUPS = "replace_popups"
    const val HEADSUP_OFF_BY_US = "headsup_off_by_us"  // local state: we switched heads-up off and owe it back

    // Camera ring: today's water traced around the camera while docked (off by default)
    const val RING_ON = "ring_on"
    const val RING_SHAPE = "ring_shape"      // 0 = circle (punch-hole), 1 = U (notch)
    const val RING_W = "ring_w_dp"
    const val RING_H = "ring_h_dp"           // U only: how deep the notch goes
    const val RING_R = "ring_r_dp"           // U only: bottom corner radius
    const val RING_T = "ring_t_dp"           // line thickness
    const val DEF_RING_W = 26
    const val DEF_RING_H = 20
    const val DEF_RING_R = 12
    const val DEF_RING_T = 2

    // Hydration
    const val WATER_ON = "water_on"
    const val GOAL = "goal_ml"
    const val GOAL_AUTO = "goal_auto"
    const val WEIGHT = "weight_kg"
    const val ACTIVITY = "activity_min"
    const val HOT = "hot_day"
    const val CUP = "cup_ml"
    const val DRINK_TYPE = "drink_type"
    const val WAKE = "wake_min"
    const val SLEEP = "sleep_min"
    const val CUPS = "cups_json"             // [{"name","ml"}] quick-add cups (synced)
    const val DEVICE_ID = "device_id"        // "phone-xxxxxxxx", local only
    const val LAST_DRINK = "last_drink_at"   // signal: a drink was logged on this phone
    const val TODAY_ML = "today_ml"          // signal/cache: hydration-adjusted ml today
    const val SNOOZE_UNTIL = "snooze_until"
    const val LAST_REMINDER = "last_reminder_at"
    const val IGNORED = "ignored_reminders"

    // v2 storage, read once to migrate into the event log
    const val TODAY_KEY = "today_key"
    const val ENTRIES = "entries_today"
    const val HISTORY = "history"

    // Pet progression (derived from the drink log; these are just signals / seen-state)
    const val PET_KIND = "pet_kind"
    const val PET_COS = "pet_cos_"           // + PetKind.name -> cosmetic index (-1 = none)
    const val BLOOMS = "blooms"
    const val ACHIEVEMENTS = "achievements"  // achievements already announced
    const val ACH_LAST = "ach_last"
    const val ACH_AT = "ach_at"
    const val GOAL_HIT_AT = "goal_hit_at"
    const val EVOLVED_AT = "evolved_at"

    // Laptop sync through TwinDesk's shared folder
    const val SYNC_TREE = "sync_tree_uri"
    const val SYNC_TS = "sync_setting_ts"    // {"GoalMl": ts, ...}
    const val SYNC_STATE = "sync_state"      // none | ok | offline | lost
    const val SYNC_AT = "sync_last_ok"
    const val SYNC_CHANGED_AT = "sync_changed_at"

    // Timer / focus
    const val TIMER_END = "timer_end_at"
    const val TIMER_TOTAL = "timer_total_ms"
    const val TIMER_DONE_AT = "timer_done_at"
    const val TIMER_PAUSED_LEFT = "timer_paused_left_ms"
    const val FOCUS_DAY = "focus_day"
    const val FOCUS_SESSIONS = "focus_sessions"
    const val FOCUS_MINUTES = "focus_minutes"

    // Feature toggles (all default on)
    const val F_CALL = "f_call"
    const val F_LIVE = "f_live"
    const val F_OTP = "f_otp"
    const val F_REPLY = "f_reply"
    const val F_BT = "f_bt"
    const val F_PRIVACY = "f_privacy"
    const val F_VOLUME = "f_volume"
    const val F_RINGER = "f_ringer"
    const val F_DANCE = "f_dance"
    const val F_DOCK = "f_dock"
    const val F_AUTO_PAY = "f_auto_pay"
    const val PAY_ACTIVE = "pay_active"
    const val PAY_APP = "pay_app"
    const val PAY_APPS = "pay_apps"
    const val F_MEDIA = "f_media"

    fun on(p: SharedPreferences, key: String) = p.getBoolean(key, true)

    const val DEF_WIDTH = 120
    const val DEF_HEIGHT = 30
    const val DEF_OFFSET_Y = 4

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
