package org.pararead.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.pararead.core.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object ModelTransferLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun forDirectory(path: String) = locks.computeIfAbsent(File(path).canonicalPath) { Mutex() }
}

/** Durable background work; the .part and verified model live on the same filesystem. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val directory = File(requireNotNull(inputData.getString("directory"))).canonicalFile
        val allowed = directory.toPath().startsWith(applicationContext.filesDir.canonicalFile.toPath()) ||
            (BuildConfig.DEBUG && directory.toPath().startsWith(applicationContext.cacheDir.canonicalFile.toPath()))
        if (!allowed) return@withContext Result.failure(workDataOf("error" to "模型目录无效"))
        ModelTransferLocks.forDirectory(directory.path).withLock {
            val name = requireNotNull(inputData.getString("file"))
            require(name == File(name).name && name.endsWith(".part"))
            val part = File(directory, name)
            val asset = ModelAsset(requireNotNull(inputData.getString("name")), requireNotNull(inputData.getString("url")),
                inputData.getLong("bytes", 0), requireNotNull(inputData.getString("sha256")))
            try {
                setForeground(notification(asset.name))
                val published = File(directory, "${asset.sha256}.gguf")
                if (published.isFile) {
                    // A process can die between atomic rename and WorkManager's success commit.
                    setProgress(workDataOf("stage" to "verify", "completed" to 0L))
                    val recovered = runCatching { ModelFiles(directory).verify(published, asset) }
                        .getOrElse { error -> if (error is CancellationException) throw error; published.delete(); null }
                    if (recovered != null) return@withLock Result.success(workDataOf("path" to recovered.path, "sha256" to recovered.sha256, "bytes" to recovered.bytes))
                }
                var lastProgress = 0L
                ModelDownload(client).fetch(asset, part) { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 250 || bytes == asset.bytes) {
                        setProgress(workDataOf("stage" to "download", "completed" to bytes)); lastProgress = now
                    }
                }
                setProgress(workDataOf("stage" to "verify", "completed" to 0L))
                val model = ModelFiles(directory).verifyAndPublish(part, asset) { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 250) {
                        setProgressAsync(workDataOf("stage" to "verify", "completed" to bytes)); lastProgress = now
                    }
                }
                Result.success(workDataOf("path" to model.path, "sha256" to model.sha256, "bytes" to model.bytes))
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) {
                // Preserve the single partial file for network retries and process recovery.
                if (runAttemptCount < 4) Result.retry()
                else Result.failure(workDataOf("error" to "下载中断，已保留进度，可重试：${e.message}"))
            } catch (e: Exception) {
                part.delete()
                Result.failure(workDataOf("error" to (e.message ?: "模型下载失败，请重试")))
            }
        }
    }

    private fun notification(model: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("models", applicationContext.withAppLanguage().getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "models")
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("ParaRead · $model")
            .setContentText(applicationContext.withAppLanguage().getString(R.string.notification_download))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .addAction(0, applicationContext.withAppLanguage().getString(R.string.cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(4101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4101, notification)
    }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
    }
}
