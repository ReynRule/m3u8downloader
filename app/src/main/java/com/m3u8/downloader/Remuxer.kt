package com.m3u8.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** TS/fMP4 -> MP4 무손실 리먹싱 (재인코딩 없음). 지원하지 않는 코덱이면 null 반환 -> 원본 그대로 저장 */
object Remuxer {
    fun toMp4(src: File, dst: File, strict: Boolean = false): Boolean = remux(src, dst, strict) != null

    /**
     * 성공하면 원본 첫 샘플의 시간(마이크로초) = 출력에서 0 이 되는 기준 시간을 돌려준다.
     * [strict] 이면 H.264/H.265/AAC 이외의 영상·오디오 트랙이 있을 때 (소리가 빠지지 않도록) 실패 처리한다.
     */
    fun remux(src: File, dst: File, strict: Boolean = false): Long? {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.absolutePath)
            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m

            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime == "video/avc" || mime == "video/hevc" || mime == "audio/mp4a-latm") {
                    ex.selectTrack(i)
                    map[i] = m.addTrack(f)
                } else if (strict && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    return null
                }
            }
            if (map.isEmpty()) return null

            m.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (base < 0) base = t
                val track = map[ex.sampleTrackIndex]
                if (track != null) {
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, maxOf(0L, t - base), flags)
                    m.writeSampleData(track, buf, info)
                }
                ex.advance()
            }
            m.stop()
            return maxOf(0L, base)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                ex.release()
            } catch (_: Exception) {
            }
            try {
                mux?.release()
            } catch (_: Exception) {
            }
        }
    }
}
