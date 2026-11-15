package com.m3u8.downloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 병렬 세그먼트 다운로드 -> (AES-128 복호화) -> 순서대로 병합 -> (선택) MP4 변환 -> 갤러리(Movies) 저장
 */
object Downloader {
    // 동시 다운로드 조각 수: START 에서 시작해 성공이 이어지면 MAX 까지 늘리고,
    // 서버가 제한(429/5xx/연결 끊김)을 걸면 MIN 까지 줄인다 (적응형).
    private const val MIN_THREADS = 4
    private const val START_THREADS = 8
    private const val MAX_THREADS = 12

    suspend fun run(ctx: Context, itemId: String) {
        withContext(Dispatchers.IO) { doRun(ctx, itemId) }
    }

    private suspend fun doRun(ctx: Context, itemId: String) {
        val item = Repo.get(itemId) ?: return
        val pl = Repo.playlistFile(itemId)
        if (!pl.exists()) throw IOException("재생목록 파일이 없습니다")

        val media = M3u8.parseMedia(pl.readText(), item.mediaUrl)
        val segs = media.segments
        if (segs.isEmpty()) throw IOException("영상 조각(segment)이 없습니다")
        val fmp4 = media.mapUrl != null

        val dir = File(ctx.filesDir, "dl/$itemId").apply { mkdirs() }
        val total = segs.size + (if (fmp4) 1 else 0)
        val done = AtomicInteger(0)
        val lastTs = AtomicLong(0)

        fun report(d: Int) {
            val now = SystemClock.elapsedRealtime()
            val prev = lastTs.get()
            if (d >= total || (now - prev > 300 && lastTs.compareAndSet(prev, now))) {
                Repo.update(itemId, persist = false) {
                    it.copy(progress = (d * 100 / total).coerceAtMost(99))
                }
            }
        }

        // 적응형 동시성 상태
        val limit = AtomicInteger(START_THREADS)
        val okStreak = AtomicInteger(0)
        val onOk: () -> Unit = {
            if (okStreak.incrementAndGet() >= 15) {
                okStreak.set(0)
                limit.updateAndGet { minOf(MAX_THREADS, it + 1) }
            }
        }
        val onThrottle: (Exception) -> Unit = {
            okStreak.set(0)
            limit.updateAndGet { maxOf(MIN_THREADS, it - 2) }
        }

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        suspend fun keyFor(uri: String): ByteArray {
            keyCache[uri]?.let { return it }
            val k = if (uri.startsWith("data:")) decodeDataUri(uri) else fetchBytes(uri, item, 0, -1)
            keyCache[uri] = k
            return k
        }

        // fMP4 초기화 세그먼트
        val initFile = File(dir, "init.bin")
        if (fmp4) {
            if (!initFile.exists() || initFile.length() == 0L) {
                initFile.writeBytes(fetchBytes(media.mapUrl!!, item, 0, -1))
            }
            report(done.incrementAndGet())
        }

        // 암호화 키는 미리 한 번씩만 받아 둠 (워커들이 동시에 같은 키를 중복 요청하지 않도록)
        segs.mapNotNull { if (it.keyMethod == "AES-128") it.keyUri else null }
            .distinct().forEach { keyFor(it) }

        // 병렬 다운로드 (이미 받은 조각은 건너뜀 -> 이어받기)
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(MAX_THREADS) { w ->
                launch {
                    while (true) {
                        ensureActive()
                        // 현재 허용 동시성보다 번호가 큰 워커는 대기 (서버 제한 시 자동 감속)
                        if (w >= limit.get()) {
                            if (next.get() >= segs.size) break
                            delay(200)
                            continue
                        }
                        val i = next.getAndIncrement()
                        if (i >= segs.size) break
                        val out = File(dir, "%06d.seg".format(i))
                        if (!(out.exists() && out.length() > 0)) {
                            val s = segs[i]
                            var data = fetchBytes(s.url, item, s.rangeStart, s.rangeLen, onThrottle)
                            if (s.keyMethod == "AES-128" && s.keyUri != null) {
                                data = decrypt(data, keyFor(s.keyUri), ivFor(s))
                            }
                            if (!fmp4) data = stripFakeHeader(data)
                            val tmp = File(dir, "%06d.tmp".format(i))
                            tmp.writeBytes(data)
                            if (!tmp.renameTo(out)) throw IOException("파일 저장 실패")
                            onOk()
                        }
                        report(done.incrementAndGet())
                    }
                }
            }
        }

        // 병합
        Repo.update(itemId) { it.copy(status = Status.MERGING, progress = 100) }
        val merged = File(dir, if (fmp4) "merged.mp4" else "merged.ts")
        merged.outputStream().buffered(1 shl 16).use { os ->
            if (fmp4) os.write(initFile.readBytes())
            for (i in segs.indices) {
                currentCoroutineContext().ensureActive()
                val f = File(dir, "%06d.seg".format(i))
                f.inputStream().use { it.copyTo(os) }
                f.delete()
            }
        }

        var finalFile = merged
        var mime = if (fmp4) "video/mp4" else "video/mp2t"
        var ext = if (fmp4) "mp4" else "ts"

        // ---- 사이트 자막 -> 영상에 합치기 (MP4 자막 트랙)
        var subInfo = ""
        var subs: List<LoadedSub> = emptyList()
        var embedded = false
        var baseMs = 0L
        if (Repo.mergeSubs && SubtitleFetcher.candidates(item).isNotEmpty()) {
            subs = SubtitleFetcher.collect(item)
            if (subs.isEmpty()) subInfo = "자막을 불러오지 못했습니다"
        }
        if (subs.isNotEmpty()) {
            // 자막 트랙은 MP4 에만 넣을 수 있으므로 'MP4 변환' 설정과 무관하게 리먹싱
            val mp4 = File(dir, "out.mp4")
            val baseUs = Remuxer.remux(merged, mp4, strict = fmp4)
            if (baseUs != null && mp4.length() > 0) {
                baseMs = baseUs / 1000
                merged.delete()
                finalFile = mp4
                mime = "video/mp4"
                ext = "mp4"
                embedded = Mp4Subtitles.embed(mp4, SubtitleFetcher.toTracks(subs, baseMs))
            } else {
                mp4.delete()
            }
            val langs = subs.joinToString(", ") { it.lang.ifBlank { it.name } }
            subInfo = if (embedded) "자막 ${subs.size}개 합침 ($langs)" else "자막을 영상에 합치지 못해 .srt 로 따로 저장"
        } else if (!fmp4 && Repo.convertMp4) {
            val mp4 = File(dir, "out.mp4")
            if (Remuxer.toMp4(merged, mp4) && mp4.length() > 0) {
                merged.delete()
                finalFile = mp4
                mime = "video/mp4"
                ext = "mp4"
            } else {
                mp4.delete()
            }
        }

        val base = sanitize(item.title) + (if (item.resolution.isNotEmpty()) "_${item.resolution}" else "")
        val name = "$base.$ext"
        val uri = saveToMediaStore(ctx, finalFile, name, mime)
        dir.deleteRecursively()

        // 합치지 못한 자막은 .srt 로 따로 저장 (플레이어가 같은 이름이면 자동으로 불러옴)
        if (!embedded) {
            for (s in subs) {
                val tag = s.lang.ifBlank { "sub" }
                saveText(ctx, "$base.$tag.srt", "application/x-subrip", SubtitleParser.toSrt(s.shifted(baseMs)))
            }
        }

        Repo.update(itemId) {
            it.copy(
                status = Status.DONE, progress = 100, outputUri = uri.toString(),
                outputName = name, error = "", subInfo = subInfo
            )
        }
    }

    /** HTTP 오류 (재시도 판단용) */
    private class HttpErr(val code: Int, val retryAfterMs: Long) : IOException("HTTP $code")

    internal suspend fun fetchBytes(
        url: String, item: VideoItem, start: Long, len: Long,
        onThrottle: ((Exception) -> Unit)? = null
    ): ByteArray {
        var last: Exception? = null
        val max = 7
        for (attempt in 0 until max) {
            currentCoroutineContext().ensureActive()
            try {
                // 앞쪽 시도는 브라우저와 같은 헤더, 뒤쪽 시도(500 등이 계속될 때)는 최소 헤더로
                val rb = Http.request(url, item, light = attempt >= 3)
                if (len > 0) rb.header("Range", "bytes=$start-${start + len - 1}")
                Http.client.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        val ra = (r.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
                        throw HttpErr(r.code, ra)
                    }
                    return r.body!!.bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                // 속도 제한/서버 오류/연결 끊김이면 호출 측이 동시성을 낮출 수 있게 알림
                if (e !is HttpErr || e.code == 429 || e.code >= 500) onThrottle?.invoke(e)
                // 영구 오류(없음/권한/잘못된 요청)는 오래 재시도하지 않음
                if (e is HttpErr && (e.code == 404 || e.code == 410 || e.code == 400) && attempt >= 1) break
                if (attempt == max - 1) break
                val backoff = minOf(8000L, 700L * (1L shl attempt)) + (0..400).random()
                val wait = if (e is HttpErr) maxOf(backoff, minOf(e.retryAfterMs, 15000L)) else backoff
                delay(wait)
            }
        }
        throw IOException("다운로드 실패: ${last?.message ?: "알 수 없는 오류"} (${Http.hostOf(url)})")
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        if (key.size != 16) throw IOException("잘못된 암호화 키(길이 ${key.size})")
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun ivFor(s: M3u8.Segment): ByteArray {
        val iv = ByteArray(16)
        val hex = s.iv
        if (hex != null) {
            var h = hex.removePrefix("0x").removePrefix("0X")
            if (h.length % 2 == 1) h = "0$h"
            val b = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val n = minOf(16, b.size)
            System.arraycopy(b, b.size - n, iv, 16 - n, n)
        } else {
            var v = s.seq
            for (i in 15 downTo 8) {
                iv[i] = (v and 0xff).toByte()
                v = v shr 8
            }
        }
        return iv
    }

    private fun decodeDataUri(uri: String): ByteArray {
        val payload = uri.substringAfter("base64,", "")
        if (payload.isEmpty()) throw IOException("지원하지 않는 키 형식")
        return Base64.decode(payload, Base64.DEFAULT)
    }

    /** 일부 사이트는 TS 앞에 가짜 이미지 헤더를 붙임 -> 0x47 동기 바이트 위치부터 사용 */
    private fun stripFakeHeader(d: ByteArray): ByteArray {
        if (d.size < 564 || d[0] == 0x47.toByte()) return d
        val limit = minOf(d.size - 376, 8192)
        for (k in 1 until limit) {
            if (d[k] == 0x47.toByte() && d[k + 188] == 0x47.toByte() && d[k + 376] == 0x47.toByte()) {
                return d.copyOfRange(k, d.size)
            }
        }
        return d
    }

    private fun sanitize(s: String): String {
        val t = s.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.').take(80)
        return t.ifBlank { "video" }
    }

    /** 자막 텍스트 파일 저장 (기기/버전에 따라 Movies 가 거부될 수 있어 Download 로 재시도, 실패는 무시) */
    private fun saveText(ctx: Context, name: String, mime: String, text: String) {
        for (rel in listOf("Movies/m3u8 downloader", "Download/m3u8 downloader")) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, mime)
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, rel)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: continue
                val os = ctx.contentResolver.openOutputStream(uri)
                if (os == null) {
                    ctx.contentResolver.delete(uri, null, null)
                    continue
                }
                os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                return
            } catch (e: Exception) {
                // 다음 위치로 재시도
            }
        }
    }

    private fun saveToMediaStore(ctx: Context, src: File, name: String, mime: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/m3u8 downloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("저장 위치를 만들 수 없습니다")
        try {
            val os = resolver.openOutputStream(uri) ?: throw IOException("저장 스트림을 열 수 없습니다")
            os.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
