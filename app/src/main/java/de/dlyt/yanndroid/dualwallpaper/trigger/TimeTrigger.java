package de.dlyt.yanndroid.dualwallpaper.trigger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;

import de.dlyt.yanndroid.dualwallpaper.Preferences;
import de.dlyt.yanndroid.dualwallpaper.utils.WallpaperUtil;

public class TimeTrigger extends BroadcastReceiver {

    public static final String ACTION_SET_NIGHT = "de.dlyt.yanndroid.dualwallpaper.SET_NIGHT";
    public static final String ACTION_SET_DAY = "de.dlyt.yanndroid.dualwallpaper.SET_DAY";

    private static final String STATE_PREFS = "time_trigger_state";
    private static final String KEY_LAST_DARK = "last_dark"; // 0 = light, 1 = dark, absent = unknown

    /** Handles alarm + time/timezone/permission changes: always resync from the clock, never trust the action. */
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) return;
        Preferences preferences = new Preferences(context);
        if (!preferences.isEnabled() || preferences.changeWithTheme()) return;

        final Context app = context.getApplicationContext();
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                applyIfChanged(app, false);
                recordFire(app, intent.getAction());
                scheduleNext(app);
            } finally {
                result.finish();
            }
        }).start();
    }

    public static void start(Context context) {
        scheduleNext(context);
        applyIfChanged(context, false);
    }

    public static void stop(Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        alarmManager.cancel(getTriggerIntent(context, true));
        alarmManager.cancel(getTriggerIntent(context, false));
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_DARK).apply();
    }

    /** Applies wallpapers only if the target state differs from what was last applied (avoids activity reload loop). */
    private static synchronized void applyIfChanged(Context context, boolean force) {
        boolean dark = isNowDark(new Preferences(context));
        SharedPreferences sp = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        if (!force && sp.contains(KEY_LAST_DARK) && sp.getInt(KEY_LAST_DARK, -1) == (dark ? 1 : 0)) return;
        new WallpaperUtil(context).loadWallpapers(dark);
        sp.edit().putInt(KEY_LAST_DARK, dark ? 1 : 0).apply();
    }

    /** Arms one low-power alarm: the next light/dark transition. Re-armed from onReceive. */
    private static void scheduleNext(Context context) {
        Preferences preferences = new Preferences(context);
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);

        // cancel both so a changed schedule never leaves a stale alarm
        alarmManager.cancel(getTriggerIntent(context, true));
        alarmManager.cancel(getTriggerIntent(context, false));

        boolean nextIsNight = !isNowDark(preferences);
        long at = getNextTimeMillis(nextIsNight ? preferences.getScheduleStart() : preferences.getScheduleEnd());
        PendingIntent pi = getTriggerIntent(context, nextIsNight);

        // Exact alarm (2/day, negligible battery). Needs "Alarms & reminders" on Android 12+;
        // falls back to an inexact, Doze-safe alarm if not granted.
        boolean exact = canScheduleExact(context);
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("next_ms", at).putBoolean("next_exact", exact).apply();
    }

    public static boolean canScheduleExact(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static void recordFire(Context context, String action) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("fire_ms", System.currentTimeMillis()).putString("fire_action", action).apply();
    }

    /** Human-readable status for the UI: last alarm, next alarm, and whether it is exact. */
    public static String getStatus(Context context) {
        SharedPreferences sp = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        long fire = sp.getLong("fire_ms", 0), next = sp.getLong("next_ms", 0);
        StringBuilder sb = new StringBuilder();
        sb.append("Last alarm: ").append(fire == 0 ? "none yet" : f.format(new Date(fire)));
        if (next != 0) sb.append("\nNext: ").append(f.format(new Date(next)))
                .append(sp.getBoolean("next_exact", false) ? " (exact)" : " (not exact)");
        return sb.toString();
    }

    public static boolean isNowDark(Preferences preferences) {
        Calendar calendar = Calendar.getInstance();
        int t = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        int start = preferences.getScheduleStart();
        int end = preferences.getScheduleEnd();
        if (start == end) return false;
        return start < end ? (t >= start && t < end) : (t >= start || t < end);
    }

    private static long getNextTimeMillis(int time) {
        Calendar calendar = Calendar.getInstance();
        long timeNow = calendar.getTimeInMillis();
        calendar.set(Calendar.HOUR_OF_DAY, time / 60);
        calendar.set(Calendar.MINUTE, time % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() - timeNow <= 0) calendar.add(Calendar.DATE, 1);
        return calendar.getTimeInMillis();
    }

    private static PendingIntent getTriggerIntent(Context context, boolean night) {
        Intent intent = new Intent(context, TimeTrigger.class);
        intent.setAction(night ? ACTION_SET_NIGHT : ACTION_SET_DAY);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
