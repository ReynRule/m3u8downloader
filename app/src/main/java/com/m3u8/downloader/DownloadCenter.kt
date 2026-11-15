package com.m3u8.downloader

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 다운로드 큐 관리 (한 번에 2개 영상, 영상당 최대 12개 조각 적응형 병렬) */
object DownloadCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val sem = Semaphore(2)

    fun enqueue(ctx: Context, ids: Collection<String>) {
        val targets = ids.filter { id ->
            val st = Repo.get(id)?.status
            st == Status.READY || st == Status.FAILED
        }
        if (targets.isEmpty()) return

        targets.forEach { id ->
            Repo.update(id) { it.copy(status = Status.QUEUED, progress = 0, error = "") }
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))

        val app = ctx.applicationContext
        targets.forEach { id ->
            jobs[id] = scope.launch {
                try {
                    sem.withPermit {
                        Repo.update(id) { it.copy(status = Status.DOWNLOADING) }
                        Downloader.run(app, id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Repo.update(id) {
                        it.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName)
                    }
                } finally {
                    jobs.remove(id)
                }
            }
        }
    }

    fun cancelAndClean(ids: Collection<String>) {
        val running = ids.mapNotNull { jobs.remove(it) }
        scope.launch(Dispatchers.IO) {
            running.forEach { it.cancelAndJoin() }
            ids.forEach { File(Repo.appContext.filesDir, "dl/$it").deleteRecursively() }
        }
    }
}
