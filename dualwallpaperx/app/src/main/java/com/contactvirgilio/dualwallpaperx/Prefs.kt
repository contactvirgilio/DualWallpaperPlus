package com.contactvirgilio.dualwallpaperx

import android.content.Context

/** All settings and switch state, in one small SharedPreferences file. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("dw", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** false = follow the phone's dark mode, true = own schedule. */
    var timeMode: Boolean
        get() = sp.getBoolean("time_mode", false)
        set(v) = sp.edit().putBoolean("time_mode", v).apply()

    /** Minutes after midnight. */
    var darkStart: Int
        get() = sp.getInt("dark_start", 20 * 60)
        set(v) = sp.edit().putInt("dark_start", v).apply()
    var darkEnd: Int
        get() = sp.getInt("dark_end", 7 * 60)
        set(v) = sp.edit().putInt("dark_end", v).apply()

    fun lockSame(dark: Boolean) = sp.getBoolean(if (dark) "dark_same" else "light_same", true)
    fun setLockSame(dark: Boolean, v: Boolean) =
        sp.edit().putBoolean(if (dark) "dark_same" else "light_same", v).apply()

    /** -1 unknown, 0 light, 1 dark. */
    var lastApplied: Int
        get() = sp.getInt("last_applied", -1)
        set(v) = sp.edit().putInt("last_applied", v).apply()
    var lastSwitch: Long
        get() = sp.getLong("last_switch", 0L)
        set(v) = sp.edit().putLong("last_switch", v).apply()
    var nextSwitch: Long
        get() = sp.getLong("next_switch", 0L)
        set(v) = sp.edit().putLong("next_switch", v).apply()
    var nextExact: Boolean
        get() = sp.getBoolean("next_exact", false)
        set(v) = sp.edit().putBoolean("next_exact", v).apply()

    /** Names of the special gradients found by shuffling. */
    var collection: Set<String>
        get() = sp.getStringSet("collection", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("collection", HashSet(v)).apply()

    /** 0 vertical, 1 diagonal, 2 horizontal. */
    var gradientDir: Int
        get() = sp.getInt("grad_dir", 1)
        set(v) = sp.edit().putInt("grad_dir", v).apply()
}
