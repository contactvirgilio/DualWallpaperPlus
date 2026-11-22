package com.contactvirgilio.dualwallpaperx

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import java.util.Calendar
import kotlin.math.min

object Switcher {
    const val ACTION_SWITCH = "com.contactvirgilio.dualwallpaperx.SWITCH"
    private val lock = Any()

    fun systemDark(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun inWindow(t: Int, start: Int, end: Int) = when {
        start == end -> false
        start < end -> t >= start && t < end
        else -> t >= start || t < end
    }

    fun isDarkNow(c: Context): Boolean {
        val p = Prefs(c)
        if (!p.timeMode) return systemDark()
        val cal = Calendar.getInstance()
        return inWindow(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), p.darkStart, p.darkEnd)
    }

    /** Sets the right wallpaper unless it's already set. Does I/O: call off the main thread. */
    fun sync(c: Context, force: Boolean = false): Boolean = synchronized(lock) {
        val p = Prefs(c)
        if (!p.enabled) return false
        val dark = isDarkNow(c)
        val want = if (dark) 1 else 0
        if (!force && p.lastApplied == want) return false
        val ok = try { Store.apply(c, dark) } catch (e: Exception) { false }
        if (ok) {
            p.lastApplied = want
            p.lastSwitch = System.currentTimeMillis()
        }
        ok
    }

    fun canExact(c: Context) =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, SwitchReceiver::class.java).setAction(ACTION_SWITCH),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Arms what the mode needs: one alarm for the next switch, or the dark mode watcher. */
    fun start(c: Context) {
        val p = Prefs(c)
        c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))
        ThemeJob.cancel(c)
        p.nextSwitch = 0
        if (!p.enabled) return
        if (p.timeMode) {
            scheduleAt(c, min(nextAt(p.darkStart), nextAt(p.darkEnd)))
        } else {
            ThemeJob.schedule(c)
            customScheduleBackstop(c)
        }
    }

    /** If the phone's dark mode runs on custom times, also wake a minute after each one. */
    private fun customScheduleBackstop(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val um = c.getSystemService(UiModeManager::class.java)
        if (um.nightMode != UiModeManager.MODE_NIGHT_CUSTOM) return
        val s = um.customNightModeStart
        val e = um.customNightModeEnd
        scheduleAt(c, min(nextAt(s.hour * 60 + s.minute), nextAt(e.hour * 60 + e.minute)) + 60_000L)
    }

    private fun scheduleAt(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val exact = canExact(c)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
        Prefs(c).apply { nextSwitch = at; nextExact = exact }
    }

    fun nextAt(minutes: Int): Long {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60)
        cal.set(Calendar.MINUTE, minutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DATE, 1)
        return cal.timeInMillis
    }

    fun refreshAsync(c: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val app = c.applicationContext
        Thread {
            sync(app, force)
            start(app)
            done?.invoke()
        }.start()
    }
}

class SwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val result = goAsync()
        Thread {
            try {
                Switcher.sync(app)
                Switcher.start(app)
            } finally {
                result.finish()
            }
        }.start()
    }
}
