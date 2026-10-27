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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sube un despacho cerrado al Google Drive de la cuenta conectada. WorkManager guarda la
 * tarea en disco: sobrevive a cierres de la app y reinicios, y espera a que haya internet.
 * Nunca marca "Sincronizado" sin la confirmación real de Google.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uuid = inputData.getString(KEY_UUID) ?: return@withContext Result.failure()
        val app = applicationContext as LilusApp
        val repo = app.repository
        val settings = app.settings
        val d = repo.getDispatch(uuid) ?: return@withContext Result.failure()
        if (d.isOpen || d.syncStatus == SyncStatus.SYNCED) return@withContext Result.success()
        if (!settings.isDriveConnected) return@withContext Result.success() // queda "pendiente"

        val items = repo.listItems(uuid)
        var pdf: File? = d.pdfPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        var xlsx: File? = d.xlsxPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
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
        val pdfBytes = pdf?.readBytes() ?: return@withContext Result.retry()
        val xlsxBytes = xlsx?.readBytes() ?: return@withContext Result.retry()

        // Un envío a la vez: evita que dos despachos creen dos carpetas "despachos".
        lock.withLock {
            try {
                val result = upload(app, settings, DriveUpload(d, items, pdfBytes, xlsxBytes))
                repo.markSynced(uuid, result.folderUrl, System.currentTimeMillis())
                Result.success()
            } catch (e: DriveException.Retryable) {
                repo.markSyncError(uuid, e.message ?: "Error de conexión. Se reintentará.")
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            } catch (e: DriveException.Unauthorized) {
                repo.markSyncError(uuid, "Hay que volver a conectar la cuenta Google en Configuración.")
                Result.failure()
            } catch (e: DriveException.Permanent) {
                repo.markSyncError(uuid, e.message ?: "No se pudo guardar en Google Drive.")
                Result.failure()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.markSyncError(uuid, "Respuesta inesperada de Google. Se reintentará.")
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
    }

    private suspend fun upload(app: LilusApp, settings: Settings, up: DriveUpload): DriveResult {
        val email = settings.googleAccount
        val cache = SettingsDriveCache(settings)
        var token = GoogleAuth.silentToken(applicationContext, email) ?: throw DriveException.Unauthorized()
        return try {
            app.driveClient.uploadDispatch(token, up, cache)
        } catch (e: DriveException.Unauthorized) {
            // Token vencido: se pide uno nuevo una vez
            GoogleAuth.invalidate(applicationContext, token)
            token = GoogleAuth.silentToken(applicationContext, email) ?: throw e
            app.driveClient.uploadDispatch(token, up, cache)
        }
    }

    companion object {
        const val KEY_UUID = "uuid"
        private const val MAX_ATTEMPTS = 20
        private val lock = Mutex()

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

        /** Reencola todo lo pendiente o con error (al abrir la app, al conectar Google, al reintentar). */
        fun enqueueAllPending(context: Context, repo: DispatchRepository, replace: Boolean = false) {
            repo.dispatchesToSync().forEach { enqueue(context, it.uuid, replace) }
        }
    }
}
