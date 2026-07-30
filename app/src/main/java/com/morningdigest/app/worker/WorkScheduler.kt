package com.morningdigest.app.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.morningdigest.app.data.prefs.AppSettings
import com.morningdigest.app.data.prefs.ScheduleMode
import java.util.Calendar
import java.util.concurrent.TimeUnit

object WorkScheduler {

    /**
     * Applies whichever schedule the user picked in Settings: a fixed daily
     * time, or a repeating "every N hours" cadence. Call this any time the
     * schedule-related settings change, on boot, and on app start.
     */
    fun applySchedule(context: Context, settings: AppSettings) {
        if (!settings.autoSendEnabled) {
            cancelDaily(context)
        } else {
            when (settings.scheduleMode) {
                ScheduleMode.DAILY -> scheduleDaily(context, settings.wakeHour, settings.wakeMinute)
                ScheduleMode.INTERVAL -> scheduleInterval(context, settings.intervalHours)
            }
        }
        applyWeatherAlertCheckSchedule(context, settings.customAlertRules.enabled)
        applyPriceAlertCheckSchedule(context, settings.priceAlertRules.enabled)
        applyPoliceIncidentSchedule(context, settings.policeAlertsEnabled)
        // YouTube notifications are push-based; never schedule periodic polling.
        cancelYoutubeChecks(context)
    }


    fun applyPoliceIncidentSchedule(context: Context, enabled: Boolean) {
        if (enabled) schedulePoliceIncidentChecks(context) else cancelPoliceIncidentChecks(context)
    }

    /** Checks public police incidents about every 2 hours when enabled. Network + battery-not-low constraints keep background work battery-friendly. */
    fun schedulePoliceIncidentChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<PoliceIncidentCheckWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PoliceIncidentCheckWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPoliceIncidentChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PoliceIncidentCheckWorker.UNIQUE_PERIODIC_NAME)
    }

    /**
     * Turns the periodic Bitcoin/currency price-threshold check on or off -
     * independent of everything else, since it's purely about catching a
     * price crossing as soon as reasonably possible.
     */
    fun applyPriceAlertCheckSchedule(context: Context, enabled: Boolean) {
        if (enabled) schedulePriceAlertChecks(context) else cancelPriceAlertChecks(context)
    }

    /** Checks the user's Bitcoin/currency price alert rules every 30 minutes - prices move faster than weather, so a tighter interval than the hourly weather check. */
    fun schedulePriceAlertChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<PriceAlertCheckWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PriceAlertCheckWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPriceAlertChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PriceAlertCheckWorker.UNIQUE_PERIODIC_NAME)
    }

    /**
     * Turns the hourly custom-weather-alert-rule check on or off - independent
     * of the main digest schedule/auto-send switch above, since the whole
     * point is a fresh heads-up notification ahead of the daily brief.
     */
    fun applyWeatherAlertCheckSchedule(context: Context, enabled: Boolean) {
        if (enabled) scheduleWeatherAlertChecks(context) else cancelWeatherAlertChecks(context)
    }

    /** Checks the user's custom weather alert rules against the forecast every hour. */
    fun scheduleWeatherAlertChecks(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<WeatherAlertCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WeatherAlertCheckWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelWeatherAlertChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WeatherAlertCheckWorker.UNIQUE_PERIODIC_NAME)
    }

    /**
     * Turns the periodic "check followed YouTube channels for new videos"
     * job on or off - on whenever at least one channel is configured in
     * Settings, off when the list is emptied.
     */
    fun applyYoutubeCheckSchedule(context: Context, enabled: Boolean) {
        if (enabled) scheduleYoutubeChecks(context) else cancelYoutubeChecks(context)
    }

    /** Checks every followed YouTube channel for new videos every 30 minutes - close to price alerts' cadence since a new upload is time-sensitive too. */
    fun scheduleYoutubeChecks(context: Context) {
        // Deliberately disabled. Polling YouTube is not used by the push
        // notification architecture and would waste battery.
        cancelYoutubeChecks(context)
    }

    fun cancelYoutubeChecks(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(YoutubeCheckWorker.UNIQUE_PERIODIC_NAME)
    }

    /**
     * Schedules (or reschedules) a daily job targeting [hour]:[minute]. WorkManager
     * periodic work doesn't support an exact time-of-day directly, so we compute
     * the initial delay until the next occurrence of that time and use a 24h
     * period from there - this survives app restarts, reboots, and Doze mode.
     */
    fun scheduleDaily(context: Context, hour: Int, minute: Int) {
        val initialDelay = millisUntilNext(hour, minute)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<MorningDigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(MorningDigestWorker.KEY_SEND_NOTIFICATION, true).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MorningDigestWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Schedules (or reschedules) a repeating job that fires every [hours] hours,
     * starting [hours] from now. Used for the "every N hours" wake-up option
     * (e.g. every 3h, 4h, 6h, 8h or 12h) instead of one fixed daily time.
     */
    fun scheduleInterval(context: Context, hours: Int) {
        val safeHours = hours.coerceIn(1, 24)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<MorningDigestWorker>(safeHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(safeHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putBoolean(MorningDigestWorker.KEY_SEND_NOTIFICATION, true).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MorningDigestWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelDaily(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MorningDigestWorker.UNIQUE_PERIODIC_NAME)
    }

    /** "Refresh Now" / "Notify Now" - runs immediately, once. */
    fun runNow(context: Context, sendNotification: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MorningDigestWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putBoolean(MorningDigestWorker.KEY_SEND_NOTIFICATION, sendNotification).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            MorningDigestWorker.UNIQUE_ONE_TIME_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun nextScheduledMillis(hour: Int, minute: Int): Long =
        System.currentTimeMillis() + millisUntilNext(hour, minute)

    /** Best-effort "next send" estimate for either schedule mode, for display in the UI. */
    fun nextScheduledMillis(settings: AppSettings, lastSentMillis: Long?): Long = when (settings.scheduleMode) {
        ScheduleMode.DAILY -> nextScheduledMillis(settings.wakeHour, settings.wakeMinute)
        ScheduleMode.INTERVAL -> {
            val intervalMillis = settings.intervalHours.coerceIn(1, 24) * 3_600_000L
            val base = lastSentMillis ?: System.currentTimeMillis()
            var next = base + intervalMillis
            while (next < System.currentTimeMillis()) next += intervalMillis
            next
        }
    }

    private fun millisUntilNext(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }
}
