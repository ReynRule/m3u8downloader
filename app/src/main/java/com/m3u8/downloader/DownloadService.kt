package com.m3u8.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 앱이 백그라운드로 가도 다운로드가 유지되도록 하는 포그라운드 서비스 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, build("다운로드 준비 중", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job == null) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            job = scope.launch {
                Repo.items.collect { all ->
                    val active = all.filter {
                        it.status == Status.QUEUED || it.status == Status.DOWNLOADING || it.status == Status.MERGING
                    }
                    if (active.isEmpty()) {
                        stopSelf(lastStartId)
                    } else {
                        val avg = active.map { if (it.status == Status.QUEUED) 0 else it.progress }
                            .average().toInt()
                        nm.notify(NOTIF_ID, build("다운로드 중 (${active.size}개)", avg))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun build(text: String, progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val nb = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("m3u8 downloader")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
        if (progress >= 0) nb.setProgress(100, progress, false) else nb.setProgress(0, 0, true)
        return nb.build()
    }

    companion object {
        private const val CHANNEL = "download"
        private const val NOTIF_ID = 1
    }
}
