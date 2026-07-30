package com.morningdigest.app.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.model.CustomAlertMatch
import com.morningdigest.app.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs hourly (independent of the main daily/interval digest schedule) to
 * check the user's custom weather alert rules against the forecast, and
 * fires a heads-up notification the moment a matched threshold enters the
 * configured lead time (e.g. "1h before temperature reaches your limit").
 *
 * Also merges the freshly-evaluated alerts into the latest saved report, so
 * the dashboard's warning icon and Weather Alerts card reflect them right
 * away instead of waiting for the next full digest refresh.
 *
 * Unlike the main daily digest (the app's core purpose, always run
 * regardless of device state), this hourly check is optional/best-effort -
 * so it politely skips a run rather than draining a critically low battery
 * or burning roaming data, and just tries again next hour.
 */
class WeatherAlertCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MorningDigestApp
        val container = app.container

        return@withContext try {
            val settings = container.settingsRepository.currentSettings()
            if (!settings.customAlertRules.enabled) return@withContext Result.success()

            if (isBatteryCriticallyLow(applicationContext)) return@withContext Result.success()
            if (isNetworkRestricted(applicationContext)) return@withContext Result.success()

            val updatedReport = container.digestRepository.refreshWeatherAlertsSection(settings)
            val matches = updatedReport.weatherAlerts.customAlerts

            val nowMillis = System.currentTimeMillis()
            // Keep only recent dedup entries (a match's exact triggerAtMillis
            // can drift slightly as the forecast updates run to run, so this
            // is a best-effort "don't spam the same crossing every hour" guard
            // rather than a perfect one).
            val staleCutoff = nowMillis - TimeUnit.HOURS.toMillis(6)
            val previousKeys = container.settingsRepository.getNotifiedAlertKeys()
                .filter { key -> key.substringAfterLast("|").toLongOrNull()?.let { it > staleCutoff } ?: false }
                .toSet()

            val imminent = matches.filter { it.leadWarning }
            val toNotify = imminent.filter { keyOf(it) !in previousKeys }

            if (toNotify.isNotEmpty()) {
                NotificationHelper.postCustomWeatherAlert(applicationContext, toNotify)
            }

            container.settingsRepository.setNotifiedAlertKeys(previousKeys + imminent.map { keyOf(it) })

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun keyOf(match: CustomAlertMatch): String =
        "${match.type}|${match.triggerAtMillis}"

    /** Skip this optional check when the battery is critically low and not charging. */
    private fun isBatteryCriticallyLow(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        return !isCharging && level in 0..CRITICAL_BATTERY_PERCENT
    }

    /**
     * Skip when the user has Data Saver turned on, or the active connection
     * is roaming - both signal "don't spend data on optional background
     * work right now". A normal metered mobile connection (not roaming, no
     * Data Saver) is still allowed, since blocking that too would mean this
     * feature barely runs for anyone without home wifi.
     */
    private fun isNetworkRestricted(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "weather_alert_check_hourly"
        private const val CRITICAL_BATTERY_PERCENT = 15
    }
}
