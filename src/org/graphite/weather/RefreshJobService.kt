package org.graphite.weather

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

/** Periodic refresh, only with a network. Failures keep the last value until it expires. */
class RefreshJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute {
            refresh(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GraphiteWeather"
        private const val JOB_PERIODIC = 1
        private const val JOB_NOW = 2
        private const val HOUR_MILLIS = 60L * 60 * 1000

        private fun refresh(context: Context) {
            val store = WeatherStore(context)
            val location = store.location
            if (!store.enabled || location == null) return
            try {
                store.saveWeather(
                    WeatherClient.fetch(store.provider, location.latitude, location.longitude),
                    System.currentTimeMillis(),
                )
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't refresh the weather: ${e.message}")
            }
            WeatherProvider.notifyChanged(context)
        }

        /** Applies the current settings, and refreshes soon if weather is on. */
        fun reschedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val store = WeatherStore(context)
            scheduler.cancelAll()
            WeatherProvider.notifyChanged(context)
            if (!store.enabled || store.location == null) return
            val service = ComponentName(context, RefreshJobService::class.java)
            scheduler.schedule(
                JobInfo.Builder(JOB_PERIODIC, service)
                    .setPeriodic(store.intervalHours * HOUR_MILLIS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
            scheduler.schedule(
                JobInfo.Builder(JOB_NOW, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }
    }
}
