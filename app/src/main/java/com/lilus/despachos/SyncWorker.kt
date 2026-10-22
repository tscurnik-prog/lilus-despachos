package com.lilus.despachos

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Envía un despacho cerrado al backend. WorkManager guarda la tarea en disco: sobrevive
 * a cierres de la app y reinicios del celular, y espera a que haya internet.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uuid = inputData.getString(KEY_UUID) ?: return@withContext Result.failure()
        val app = applicationContext as LilusApp
        val repo = app.repository
        val d = repo.getDispatch(uuid) ?: return@withContext Result.failure()
        if (d.isOpen || d.syncStatus == SyncStatus.SYNCED) return@withContext Result.success()

        val settings = Settings(applicationContext)
        if (!settings.isBackendConfigured) return@withContext Result.success() // queda "pendiente"

        val items = repo.listItems(uuid)
        var pdf = d.pdfPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
        var xlsx = d.xlsxPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
        if (pdf == null || xlsx == null) {
            try {
                val (p, x) = Documents.generate(applicationContext, d, items)
                repo.setDocuments(uuid, p.absolutePath, x.absolutePath)
                pdf = p; xlsx = x
            } catch (e: Exception) {
                repo.markSyncError(uuid, "No se pudieron generar los comprobantes en el celular.")
                return@withContext Result.retry()
            }
        }

        when (val r = app.syncClient.submit(settings.backendUrl, settings.deviceToken, d, items, pdf, xlsx)) {
            is SyncOutcome.Success -> {
                repo.markSynced(uuid, r.driveUrl, System.currentTimeMillis())
                Result.success()
            }
            is SyncOutcome.Retryable -> {
                repo.markSyncError(uuid, r.message)
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
            is SyncOutcome.Permanent -> {
                repo.markSyncError(uuid, r.message)
                Result.failure()
            }
            SyncOutcome.NotConfigured -> Result.success()
        }
    }

    companion object {
        const val KEY_UUID = "uuid"
        private const val MAX_ATTEMPTS = 20

        fun enqueue(context: Context, uuid: String, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_UUID to uuid))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("sync")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-$uuid",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        /** Reencola todo lo pendiente o con error (al abrir la app, al guardar la configuración, al reintentar). */
        fun enqueueAllPending(context: Context, repo: DispatchRepository, replace: Boolean = false) {
            repo.dispatchesToSync().forEach { enqueue(context, it.uuid, replace) }
        }
    }
}
