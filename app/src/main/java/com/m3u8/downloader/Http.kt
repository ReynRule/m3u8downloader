package com.m3u8.downloader

import android.webkit.CookieManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun hostOf(u: String): String = u.toHttpUrlOrNull()?.host ?: ""

    private fun originOf(u: String): String? {
        val h = u.toHttpUrlOrNull() ?: return null
        val port = if (h.port != HttpUrl.defaultPort(h.scheme)) ":${h.port}" else ""
        return "${h.scheme}://${h.host}$port"
    }

    private fun cookieFor(url: String, item: VideoItem): String {
        val c = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Throwable) {
            null
        }
        if (!c.isNullOrBlank()) return c
        return if (hostOf(url) == hostOf(item.sourceUrl)) item.cookie else ""
    }

    /** 브라우저에서 감지했을 때와 동일한 UA / Referer / Cookie 로 요청 */
    fun request(url: String, item: VideoItem): Request.Builder {
        val b = Request.Builder().url(url)
        try {
            if (item.userAgent.isNotBlank()) b.header("User-Agent", item.userAgent)
            val ref = item.referer.ifBlank { item.pageUrl }
            if (ref.isNotBlank()) {
                b.header("Referer", ref)
                originOf(ref)?.let { b.header("Origin", it) }
            }
            val cookie = cookieFor(url, item)
            if (cookie.isNotBlank()) b.header("Cookie", cookie)
        } catch (e: IllegalArgumentException) {
            // 헤더에 허용되지 않는 문자가 있으면 해당 헤더만 건너뜀
        }
        return b
    }

    fun getText(url: String, item: VideoItem): String =
        client.newCall(request(url, item).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.peekBody(5_000_000).string()
        }
}
