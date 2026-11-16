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
        val t0 = SystemClock.elapsedRealtime()
        Dbg.i("DL", "시작 '${item.title}' ${item.resolution} id=$itemId")
        val pl = Repo.playlistFile(itemId)
        if (!pl.exists()) throw IOException("재생목록 파일이 없습니다")

        val media = M3u8.parseMedia(pl.readText(), item.mediaUrl)
        val segs = media.segments
        if (segs.isEmpty()) throw IOException("영상 조각(segment)이 없습니다")
        val fmp4 = media.mapUrl != null

        // 소리가 영상과 따로 있는 HLS(별도 오디오 트랙)
        val apl = Repo.audioPlaylistFile(itemId)
        val amedia = if (item.audioUrl.isNotBlank() && apl.exists()) M3u8.parseMedia(apl.readText(), item.audioUrl) else null
        val asegs = amedia?.segments ?: emptyList()
        val hasAudio = asegs.isNotEmpty()
        val afmp4 = hasAudio && amedia?.mapUrl != null

        Dbg.i("DL", "영상 조각 ${segs.size}개 fmp4=$fmp4 map=${media.mapUrl} | 오디오 ${if (hasAudio) "분리 ${asegs.size}개 afmp4=$afmp4 map=${amedia?.mapUrl}" else "분리 아님(영상에 포함 or 없음)"}")
        Dbg.i("DL", "첫 영상 조각 ${segs.first().url} key=${segs.first().keyMethod}")
        if (hasAudio) Dbg.i("DL", "첫 오디오 조각 ${asegs.first().url} key=${asegs.first().keyMethod}")
        Dbg.i("DL", "설정 convertMp4=${Repo.convertMp4} mergeSubs=${Repo.mergeSubs}")

        val dir = File(ctx.filesDir, "dl/$itemId").apply { mkdirs() }
        val total = segs.size + asegs.size + (if (fmp4) 1 else 0) + (if (afmp4) 1 else 0)
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
            val prev = limit.getAndUpdate { maxOf(MIN_THREADS, it - 2) }
            if (prev > MIN_THREADS) Dbg.i("DL", "서버 제한(${it.message}) -> 동시 ${maxOf(MIN_THREADS, prev - 2)}개로 감속")
        }

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        suspend fun keyFor(uri: String): ByteArray {
            keyCache[uri]?.let { return it }
            val k = if (uri.startsWith("data:")) decodeDataUri(uri) else fetchBytes(uri, item, 0, -1)
            keyCache[uri] = k
            return k
        }

        // fMP4 초기화 세그먼트 (영상 / 오디오)
        val initFile = File(dir, "init.bin")
        if (fmp4) {
            if (!initFile.exists() || initFile.length() == 0L) {
                initFile.writeBytes(fetchBytes(media.mapUrl!!, item, media.mapStart, media.mapLen))
            }
            Dbg.i("DL", "영상 init ${initFile.length()}B (range ${media.mapStart}/${media.mapLen}) ${Dbg.hex(initFile.readBytes())}")
            report(done.incrementAndGet())
        }
        val ainitFile = File(dir, "ainit.bin")
        if (afmp4) {
            if (!ainitFile.exists() || ainitFile.length() == 0L) {
                ainitFile.writeBytes(fetchBytes(amedia!!.mapUrl!!, item, amedia.mapStart, amedia.mapLen))
            }
            Dbg.i("DL", "오디오 init ${ainitFile.length()}B (range ${amedia!!.mapStart}/${amedia.mapLen}) ${Dbg.hex(ainitFile.readBytes())}")
            report(done.incrementAndGet())
        }

        // 영상 + 오디오 조각을 하나의 작업 목록으로 합쳐 같은 워커 풀에서 병렬 처리
        class Task(val seg: M3u8.Segment, val name: String, val stripHeader: Boolean)
        val tasks = ArrayList<Task>(segs.size + asegs.size)
        segs.forEachIndexed { i, s -> tasks.add(Task(s, "%06d.seg".format(i), !fmp4)) }
        asegs.forEachIndexed { i, s -> tasks.add(Task(s, "a%06d.seg".format(i), !afmp4)) }

        // 암호화 키는 미리 한 번씩만 받아 둠 (워커들이 동시에 같은 키를 중복 요청하지 않도록)
        tasks.mapNotNull { if (it.seg.keyMethod == "AES-128") it.seg.keyUri else null }
            .distinct().forEach { keyFor(it) }

        // 병렬 다운로드 (이미 받은 조각은 건너뜀 -> 이어받기)
        val skipped = tasks.count { File(dir, it.name).let { f -> f.exists() && f.length() > 0 } }
        if (skipped > 0) Dbg.i("DL", "이어받기: 이미 받은 조각 $skipped/${tasks.size}개 건너뜀")
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(MAX_THREADS) { w ->
                launch {
                    while (true) {
                        ensureActive()
                        // 현재 허용 동시성보다 번호가 큰 워커는 대기 (서버 제한 시 자동 감속)
                        if (w >= limit.get()) {
                            if (next.get() >= tasks.size) break
                            delay(200)
                            continue
                        }
                        val i = next.getAndIncrement()
                        if (i >= tasks.size) break
                        val t = tasks[i]
                        val out = File(dir, t.name)
                        if (!(out.exists() && out.length() > 0)) {
                            val s = t.seg
                            var data = fetchBytes(s.url, item, s.rangeStart, s.rangeLen, onThrottle)
                            if (s.keyMethod == "AES-128" && s.keyUri != null) {
                                data = decrypt(data, keyFor(s.keyUri), ivFor(s))
                            }
                            val raw = data
                            if (t.stripHeader) data = stripFakeHeader(stripId3(data))
                            // 첫 조각은 포맷 확인용으로 앞 바이트 기록 (47=TS, ff f1=ADTS AAC, 49 44 33=ID3)
                            if (t.name == "000000.seg" || t.name == "a000000.seg") {
                                Dbg.i("DL", "${t.name} ${raw.size}B 원본 [${Dbg.hex(raw)}] -> 정리 후 ${data.size}B [${Dbg.hex(data)}]")
                            }
                            val tmp = File(dir, t.name.removeSuffix(".seg") + ".tmp")
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
        Dbg.i("DL", "조각 다운로드 완료 ${(SystemClock.elapsedRealtime() - t0) / 1000}s, 최종 동시성 ${limit.get()}")
        Repo.update(itemId) { it.copy(status = Status.MERGING, progress = 100) }
        suspend fun mergeStream(dst: File, init: File?, names: List<String>) {
            dst.outputStream().buffered(1 shl 16).use { os ->
                if (init != null) os.write(init.readBytes())
                for (n in names) {
                    currentCoroutineContext().ensureActive()
                    val f = File(dir, n)
                    f.inputStream().use { it.copyTo(os) }
                    f.delete()
                }
            }
        }
        val merged = File(dir, if (fmp4) "merged.mp4" else "merged.ts")
        mergeStream(merged, if (fmp4) initFile else null, tasks.filter { !it.name.startsWith("a") }.map { it.name })
        Dbg.i("DL", "영상 병합 " + Remuxer.describe(merged))

        var finalFile = merged
        var mime = if (fmp4) "video/mp4" else "video/mp2t"
        var ext = if (fmp4) "mp4" else "ts"
        var muxedBaseUs: Long? = null

        // ---- 영상과 소리가 따로 받아졌으면 하나의 MP4 로 합침 (합치지 못하면 소리 없는 파일이 되므로 실패 처리)
        if (hasAudio) {
            val audioMerged = File(dir, if (afmp4) "audio.mp4" else "audio.ts")
            mergeStream(audioMerged, if (afmp4) ainitFile else null, tasks.filter { it.name.startsWith("a") }.map { it.name })
            Dbg.i("DL", "오디오 병합 " + Remuxer.describe(audioMerged))
            val muxed = File(dir, "muxed.mp4")
            val baseUs = Remuxer.mux(merged, audioMerged, muxed)
            Dbg.i("DL", "영상+오디오 합치기 결과 baseUs=$baseUs size=${muxed.length()}")
            audioMerged.delete()
            if (baseUs == null || muxed.length() == 0L) {
                muxed.delete()
                throw IOException("영상과 소리를 합치지 못했습니다 (지원하지 않는 오디오/영상 형식)")
            }
            merged.delete()
            finalFile = muxed
            mime = "video/mp4"
            ext = "mp4"
            muxedBaseUs = baseUs
        }

        // ---- 사이트 자막 -> 영상에 합치기 (MP4 자막 트랙)
        var subInfo = ""
        var subs: List<LoadedSub> = emptyList()
        var embedded = false
        var baseMs = 0L
        if (Repo.mergeSubs && SubtitleFetcher.candidates(item).isNotEmpty()) {
            subs = SubtitleFetcher.collect(item)
            Dbg.i("DL", "자막 후보 ${SubtitleFetcher.candidates(item).size}개 -> 불러옴 ${subs.size}개")
            if (subs.isEmpty()) subInfo = "자막을 불러오지 못했습니다"
        }
        if (subs.isNotEmpty()) {
            if (muxedBaseUs != null) {
                // 이미 MP4(영상+소리)로 합쳐진 상태 -> 바로 자막 트랙 추가
                baseMs = muxedBaseUs / 1000
                embedded = Mp4Subtitles.embed(finalFile, SubtitleFetcher.toTracks(subs, baseMs))
            } else {
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
            }
            val langs = subs.joinToString(", ") { it.lang.ifBlank { it.name } }
            Dbg.i("DL", "자막 합치기 embedded=$embedded")
            subInfo = if (embedded) "자막 ${subs.size}개 합침 ($langs)" else "자막을 영상에 합치지 못해 .srt 로 따로 저장"
        } else if (!fmp4 && !hasAudio && Repo.convertMp4) {
            val mp4 = File(dir, "out.mp4")
            val ok = Remuxer.toMp4(merged, mp4) && mp4.length() > 0
            Dbg.i("DL", "MP4 변환 ${if (ok) "성공" else "실패 -> .ts 그대로 저장"}")
            if (ok) {
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
        // 최종 파일에 소리 트랙이 실제로 있는지 확인 (소리 안 남 진단용)
        Dbg.i("DL", "최종 " + Remuxer.describe(finalFile))
        if (!Remuxer.hasAudio(finalFile)) Dbg.e("DL", "최종 파일에 오디오 트랙 없음! (소리 안 나는 원인)")
        val uri = saveToMediaStore(ctx, finalFile, name, mime)
        Dbg.i("DL", "저장 완료 $name ($mime) -> $uri, 총 ${(SystemClock.elapsedRealtime() - t0) / 1000}s")
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

    /** 브라우저 헤더(Origin/Sec-Fetch)를 거부하고 최소 헤더에만 응답하는 호스트 */
    private val lightHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    internal suspend fun fetchBytes(
        url: String, item: VideoItem, start: Long, len: Long,
        onThrottle: ((Exception) -> Unit)? = null
    ): ByteArray {
        var last: Exception? = null
        val max = 7
        val host = Http.hostOf(url)
        for (attempt in 0 until max) {
            currentCoroutineContext().ensureActive()
            // 브라우저 헤더와 최소 헤더를 번갈아 시도. 최소 헤더로만 되는 호스트는 기억해서
            // 이후 조각은 처음부터 최소 헤더로 받는다 (안 그러면 조각마다 500 을 여러 번 맞고
            // 백오프하느라 다운로드가 수십 배 느려짐)
            val preferLight = host in lightHosts
            val light = (attempt % 2 == 1) != preferLight
            try {
                val rb = Http.request(url, item, light = light)
                if (len > 0) rb.header("Range", "bytes=$start-${start + len - 1}")
                Http.client.newCall(rb.build()).execute().use { r ->
                    if (!r.isSuccessful) {
                        val ra = (r.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
                        throw HttpErr(r.code, ra)
                    }
                    val bytes = r.body!!.bytes()
                    if (light && !preferLight && last is HttpErr && lightHosts.add(host)) {
                        Dbg.i("HTTP", "$host: 최소 헤더로만 응답 -> 이후 최소 헤더 사용")
                    }
                    return bytes
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                // 속도 제한/서버 오류/연결 끊김이면 호출 측이 동시성을 낮출 수 있게 알림
                // (브라우저 헤더로 받은 5xx 는 헤더 거부일 수 있으므로 제한으로 보지 않음)
                if (e !is HttpErr || e.code == 429 || (e.code >= 500 && light)) onThrottle?.invoke(e)
                // 영구 오류(없음/권한/잘못된 요청)는 오래 재시도하지 않음
                if (e is HttpErr && (e.code == 404 || e.code == 410 || e.code == 400) && attempt >= 1) break
                if (attempt == max - 1) break
                val backoff = minOf(8000L, 700L * (1L shl attempt)) + (0..400).random()
                val wait = if (e is HttpErr) maxOf(backoff, minOf(e.retryAfterMs, 15000L)) else backoff
                Dbg.i("HTTP", "재시도 ${attempt + 1}/$max ${e.message} light=$light ${wait}ms 후 | ${url.substringAfterLast('/').take(80)}")
                delay(wait)
            }
        }
        Dbg.e("HTTP", "최종 실패 $url", last)
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

    /**
     * 패킹된 오디오(.aac 조각) 앞의 ID3 태그(타임스탬프 정보)를 제거.
     * 남겨 두면 조각을 이어 붙인 파일에서 두 번째 조각의 ID3 를 만나는 순간 오디오 읽기가 끝나
     * 첫 조각(수 초) 뒤로 소리가 사라진다.
     * ID3 의 시작 시각(PRIV transportStreamTimestamp)은 쓰지 않으므로 영상과 소리의 시작 차이만큼
     * (보통 수십 ms) 어긋날 수 있다. 더 정확히 맞춰야 하면 그 값으로 오디오 시간을 보정할 것.
     */
    private fun stripId3(d: ByteArray): ByteArray {
        var p = 0
        while (d.size - p >= 10 && d[p] == 0x49.toByte() && d[p + 1] == 0x44.toByte() && d[p + 2] == 0x33.toByte()) {
            val size = (d[p + 6].toInt() and 0x7f shl 21) or (d[p + 7].toInt() and 0x7f shl 14) or
                (d[p + 8].toInt() and 0x7f shl 7) or (d[p + 9].toInt() and 0x7f)
            val footer = if (d[p + 5].toInt() and 0x10 != 0) 10 else 0
            p += 10 + size + footer
        }
        return if (p == 0) d else d.copyOfRange(minOf(p, d.size), d.size)
    }

    /** 일부 사이트는 TS 앞에 가짜 이미지 헤더를 붙임 -> 0x47 동기 바이트 위치부터 사용 */
    private fun stripFakeHeader(d: ByteArray): ByteArray {
        if (d.size < 564 || d[0] == 0x47.toByte()) return d
        // ID3 태그 / ADTS(AAC) 로 시작하는 오디오 조각은 건드리지 않음
        if (d[0] == 0x49.toByte() && d[1] == 0x44.toByte() && d[2] == 0x33.toByte()) return d
        if (d[0] == 0xFF.toByte() && (d[1].toInt() and 0xF0) == 0xF0) return d
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
                Dbg.e("DL", "자막 파일 저장 실패 ($rel/$name)", e)
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
