package com.m3u8.downloader

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * 재생목록의 첫 조각(fMP4 면 init + 첫 조각)만 받아서 안에 어떤 트랙이 있는지 본다.
 * - video=true, audio=false : 영상만 있는 조각 (소리는 다른 m3u8 로 따로 오는 경우 -> 짝을 찾아야 함)
 * - video=false, audio=true : 오디오 전용 재생목록
 * - video=true, audio=true  : 소리가 영상에 들어 있음
 * 확인하지 못하면 null (기존 동작 그대로 진행).
 */
object MediaProbe {
    class Result(val video: Boolean, val audio: Boolean)

    /** 포맷 확인에 쓸 최대 바이트 (TS 한 조각이면 영상/소리가 모두 들어올 만큼) */
    private const val MAX_BYTES = 4 * 1024 * 1024

    suspend fun probe(item: VideoItem, media: M3u8.Media): Result? {
        val seg = media.segments.firstOrNull() ?: return null
        val f = File(Repo.appContext.cacheDir, "probe_${item.id}.bin")
        return try {
            val out = java.io.ByteArrayOutputStream()
            if (media.mapUrl != null) {
                out.write(Downloader.fetchBytes(media.mapUrl, item, media.mapStart, media.mapLen))
            }
            var data = Downloader.fetchBytes(seg.url, item, seg.rangeStart, seg.rangeLen, null, MAX_BYTES)
            if (seg.keyMethod == "AES-128" && seg.keyUri != null) {
                // 부분만 받았을 수 있으므로 16바이트 블록 단위로 잘라 복호화 (CBC 라 앞부분만 풀어도 정상)
                val key = if (seg.keyUri.startsWith("data:")) Downloader.decodeDataUri(seg.keyUri)
                else Downloader.fetchBytes(seg.keyUri, item, 0, -1)
                val n = data.size - data.size % 16
                data = Downloader.decryptPartial(data.copyOf(n), key, Downloader.ivFor(seg))
            }
            if (media.mapUrl == null) {
                // TS / 패킹 오디오: 앞의 ID3·가짜 헤더 제거
                val skip = StreamUtil.headSkip(data.copyOf(minOf(data.size, StreamUtil.HEAD_BYTES)), data.size.toLong())
                if (skip > 0) data = data.copyOfRange(minOf(skip, data.size), data.size)
            }
            out.write(data)
            f.writeBytes(out.toByteArray())
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                var v = false
                var a = false
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) v = true
                    if (mime.startsWith("audio/")) a = true
                }
                if (!v && !a) null else Result(v, a)
            } finally {
                ex.release()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Dbg.e("Probe", "조각 분석 실패 (소리/영상 구성을 확인하지 못함)", e)
            null
        } finally {
            f.delete()
        }
    }
}
