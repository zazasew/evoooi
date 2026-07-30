package com.morningdigest.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.notification.NotificationHelper

/** Battery-conscious local police check. WorkManager decides the exact execution time. */
class PoliceIncidentCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MorningDigestApp
        val settings = app.container.settingsRepository.currentSettings()
        if (!settings.policeAlertsEnabled) return Result.success()
        return runCatching {
            val incidents = app.container.policeIncidentFetcher.fetch(settings.policeMunicipalities, settings.policeCategories, 30)
            val seen = app.container.settingsRepository.getPoliceSeenIds()
            val dismissed = app.container.settingsRepository.getPoliceDismissedIds()
            val activeIncidents = incidents.filter { it.id !in dismissed }
            if (seen.isEmpty() && activeIncidents.isNotEmpty()) {
                app.container.settingsRepository.setPoliceSeenIds(activeIncidents.map { it.id }.toList().takeLast(200).toSet())
                return@runCatching Result.success()
            }
            val fresh = activeIncidents.filter { it.id !in seen }
            if (fresh.isNotEmpty()) {
                NotificationHelper.postPoliceIncidents(applicationContext, fresh.take(5))
                app.container.settingsRepository.setPoliceSeenIds((seen + activeIncidents.map { it.id }).toList().takeLast(200).toSet())
            } else if (incidents.isNotEmpty()) {
                app.container.settingsRepository.setPoliceSeenIds((seen + activeIncidents.map { it.id }).toList().takeLast(200).toSet())
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object { const val UNIQUE_PERIODIC_NAME = "police_incident_checks" }
}
