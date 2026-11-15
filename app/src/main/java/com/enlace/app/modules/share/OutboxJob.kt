package com.enlace.app.modules.share

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/**
 * Runs when the device is on Wi-Fi with something waiting in [Outbox]: tries
 * to deliver it. Any Wi-Fi wakes it, not only the home one, so while items
 * are still waiting it asks to be run again in a quarter of an hour. With
 * nothing waiting it is never scheduled: no background work, no battery.
 */
class OutboxJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "enlace-outbox") {
            Sender.flush(applicationContext)
            if (Outbox.count(applicationContext) > 0) Outbox.schedule(applicationContext, RETRY_MS)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Outbox.schedule(applicationContext, RETRY_MS)
        return false
    }

    companion object {
        const val ID = 4701
        const val RETRY_MS = 15L * 60 * 1000
    }
}
