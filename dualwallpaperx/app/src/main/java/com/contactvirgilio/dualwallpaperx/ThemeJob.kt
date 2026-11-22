package com.contactvirgilio.dualwallpaperx

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * One-shot job that Android starts when the dark mode setting changes. It re-arms itself each time,
 * so nothing runs between changes.
 */
class ThemeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            Switcher.sync(app)
            jobFinished(params, false)
            val p = Prefs(app)
            if (p.enabled && !p.timeMode) schedule(app)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    companion object {
        private const val ID = 42

        fun schedule(c: Context) {
            val info = JobInfo.Builder(ID, ComponentName(c, ThemeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(Settings.Secure.getUriFor("ui_night_mode"), 0))
                .setTriggerContentUpdateDelay(500)
                .setTriggerContentMaxDelay(2000)
                .build()
            c.getSystemService(JobScheduler::class.java).schedule(info)
        }

        fun cancel(c: Context) = c.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
