package io.github.zvensmoluya.vortexcue.synctext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.zvensmoluya.vortexcue.R
import io.github.zvensmoluya.vortexcue.app.MainActivity
import io.github.zvensmoluya.vortexcue.app.appContainer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AndroidSyncTextScheduler(
    context: Context,
    private val diagnostics: SyncTextDiagnosticRecorder = NoOpSyncTextDiagnosticRecorder,
) : SyncTextScheduler {
    private val appContext = context.applicationContext
    private val uidtScheduleGate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val scheduler = appContext.getSystemService(JobScheduler::class.java)
            UidtScheduleGate(
                hasPendingJob = { scheduler.getPendingJob(UidtJobId) != null },
                submit = { scheduler.schedule(uidtJobInfo()) },
                successCode = JobScheduler.RESULT_SUCCESS,
            )
        } else {
            null
        }
    }

    override fun schedule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val outcome = uidtScheduleGate?.schedule() ?: UidtScheduleOutcome.DeferredUntilForeground
            diagnostics.recordAsync(
                SyncTextDiagnosticEventType.ScheduleOutcome,
                stage = "UIDT",
                outcome = outcome.name,
                reason = "ScheduleRequested",
            )
        } else {
            val request =
                OneTimeWorkRequestBuilder<SyncTextQueueWorker>()
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(appContext)
                .enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request)
            diagnostics.recordAsync(
                SyncTextDiagnosticEventType.ScheduleOutcome,
                stage = "WorkManager",
                outcome = "Enqueued",
                reason = "ScheduleRequested",
            )
        }
    }

    override fun cancel(taskId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            appContext.getSystemService(JobScheduler::class.java).cancel(UidtJobId)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(WorkName)
        }
    }

    fun onAppForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            uidtScheduleGate?.retryDeferred()?.let { outcome ->
                diagnostics.recordAsync(
                    SyncTextDiagnosticEventType.ScheduleOutcome,
                    stage = "UIDT",
                    outcome = outcome.name,
                    reason = "ForegroundRetry",
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun uidtJobInfo(): JobInfo =
        JobInfo.Builder(UidtJobId, ComponentName(appContext, SyncTextUidtJobService::class.java))
            .setUserInitiated(true)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()

    companion object {
        internal const val WorkName = "sync-text-queue"
        internal const val UidtJobId = 0x56545854
    }
}

internal class UidtScheduleGate(
    private val hasPendingJob: () -> Boolean,
    private val submit: () -> Int,
    private val successCode: Int,
) {
    private var deferredUntilForeground = false

    @Synchronized
    fun schedule(): UidtScheduleOutcome {
        val outcome =
            try {
                if (hasPendingJob()) {
                    UidtScheduleOutcome.AlreadyScheduled
                } else if (submit() == successCode) {
                    UidtScheduleOutcome.Scheduled
                } else {
                    UidtScheduleOutcome.DeferredUntilForeground
                }
            } catch (_: RuntimeException) {
                UidtScheduleOutcome.DeferredUntilForeground
            }
        deferredUntilForeground = outcome == UidtScheduleOutcome.DeferredUntilForeground
        return outcome
    }

    @Synchronized
    fun retryDeferred(): UidtScheduleOutcome? =
        if (deferredUntilForeground) schedule() else null
}

internal enum class UidtScheduleOutcome {
    Scheduled,
    AlreadyScheduled,
    DeferredUntilForeground,
}

class SyncTextQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        setForeground(notificationInfo())
        val container = applicationContext.appContainer
        container.syncTextDiagnostics.record(
            SyncTextDiagnosticEventType.RunnerLifecycle,
            stage = "WorkManager",
            outcome = "Started",
        )
        container.syncTextTaskRunner.runUntilIdle()
        val retry = container.syncTextRepository.hasRetryableNetworkWaitingTasks()
        container.syncTextDiagnostics.record(
            SyncTextDiagnosticEventType.RunnerLifecycle,
            stage = "WorkManager",
            outcome = if (retry) "Retry" else "Completed",
        )
        return if (retry) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notificationInfo()

    private fun notificationInfo(): ForegroundInfo {
        val notification = SyncTextNotification.build(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SyncTextNotification.NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(SyncTextNotification.NotificationId, notification)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class SyncTextUidtJobService : JobService() {
    private var running: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        applicationContext.appContainer.syncTextDiagnostics.recordAsync(
            SyncTextDiagnosticEventType.RunnerLifecycle,
            stage = "UIDT",
            outcome = "Started",
        )
        setNotification(
            params,
            SyncTextNotification.NotificationId,
            SyncTextNotification.build(this),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        running?.cancel()
        running =
            scope.launch {
                val container = applicationContext.appContainer
                container.syncTextTaskRunner.runUntilIdle()
                val retry = container.syncTextRepository.hasRetryableNetworkWaitingTasks()
                container.syncTextDiagnostics.record(
                    SyncTextDiagnosticEventType.RunnerLifecycle,
                    stage = "UIDT",
                    outcome = if (retry) "Retry" else "Completed",
                )
                jobFinished(params, retry)
            }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        applicationContext.appContainer.syncTextDiagnostics.recordAsync(
            SyncTextDiagnosticEventType.RunnerLifecycle,
            stage = "UIDT",
            outcome = "StoppedBySystem",
        )
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class SyncTextNetworkMonitor(
    context: Context,
    private val repository: SyncTextRepository,
    private val networkState: SyncTextNetworkState,
    private val scheduler: SyncTextScheduler,
    private val scope: CoroutineScope,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wakeQueue()

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = wakeQueue()
        }

    fun start() {
        if (registered) return
        registered = runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
    }

    private fun wakeQueue() {
        scope.launch {
            repository.wakeEligibleNetworkTasks(networkState)
            scheduler.schedule()
        }
    }
}

private object SyncTextNotification {
    const val NotificationId = 0x53595458
    private const val ChannelId = "sync_text_generation"

    fun build(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "同步文字生成", NotificationManager.IMPORTANCE_LOW),
        )
        val openApp =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在生成同步文字")
            .setContentText("任务会按队列继续，完成结果将直接进入播放器")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
}
