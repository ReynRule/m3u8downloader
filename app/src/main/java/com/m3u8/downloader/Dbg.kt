package com.m3u8.downloader

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 디버그 로그: filesDir/debug.log 에 한 줄씩 기록 (앱 재시작·크래시 후에도 남음) + logcat 미러.
 * 2MB 가 넘으면 debug.old.log 로 넘기고 새로 시작 (최대 약 4MB 보관).
 * 쿠키 값은 기록하지 않는다. URL 은 기록하므로(토큰 포함 가능) 공유할 때 주의.
 */
object Dbg {
    private const val TAG = "m3u8dl"
    private const val MAX = 2L * 1024 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private lateinit var file: File
    private lateinit var old: File

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "debug.log")
        old = File(ctx.filesDir, "debug.old.log")
    }

    fun i(tag: String, msg: String) = write(tag, msg, null)

    fun e(tag: String, msg: String, t: Throwable? = null) = write(tag, "⚠ $msg", t)

    @Synchronized
    private fun write(tag: String, msg: String, t: Throwable?) {
        if (t == null) Log.i(TAG, "[$tag] $msg") else Log.w(TAG, "[$tag] $msg", t)
        if (!::file.isInitialized) return
        try {
            val sb = StringBuilder()
            sb.append(fmt.format(Date())).append(" [").append(Thread.currentThread().name).append("] [")
                .append(tag).append("] ").append(msg).append('\n')
            if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')
            if (file.length() > MAX) {
                old.delete()
                file.renameTo(old)
            }
            file.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun dump(): String {
        if (!::file.isInitialized) return ""
        return (if (old.exists()) old.readText() else "") + (if (file.exists()) file.readText() else "")
    }

    @Synchronized
    fun clear() {
        old.delete()
        file.delete()
    }

    /** 바이트 앞부분 hex (조각 포맷 확인용) */
    fun hex(b: ByteArray, n: Int = 16): String =
        b.take(n).joinToString(" ") { "%02x".format(it) }
}
