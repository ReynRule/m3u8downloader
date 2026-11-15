package com.m3u8.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * TS/fMP4 -> MP4 무손실 리먹싱 (재인코딩 없음).
 * MP4 에 넣을 수 없는 코덱(AC3 등)이 하나라도 있으면 null 을 돌려준다 -> 소리/영상이 조용히 빠지는 대신
 * 호출 측이 원본(TS) 그대로 저장하거나 오류로 처리한다.
 */
object Remuxer {
    fun toMp4(src: File, dst: File, strict: Boolean = false): Boolean = remux(src, dst, strict) != null

    /** 단일 파일 리먹싱. [strict] 는 호환을 위해 남겨 둔 값 (이제 항상 엄격하게 동작) */
    @Suppress("UNUSED_PARAMETER")
    fun remux(src: File, dst: File, strict: Boolean = false): Long? = process(listOf(src), dst)

    /** 영상 파일 + 별도 오디오 파일을 하나의 MP4 로 합친다 (타임스탬프 기준을 공유해 싱크 유지) */
    fun mux(video: File, audio: File, dst: File): Long? = process(listOf(video, audio), dst)

    /** 성공하면 입력들의 첫 샘플 중 가장 이른 시간(마이크로초) = 출력에서 0 이 되는 기준 시간 */
    private fun process(srcs: List<File>, dst: File): Long? {
        val exs = ArrayList<MediaExtractor>()
        var mux: MediaMuxer? = null
        try {
            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m

            val maps = ArrayList<HashMap<Int, Int>>()
            for (src in srcs) {
                val ex = MediaExtractor()
                exs.add(ex)
                ex.setDataSource(src.absolutePath)
                val map = HashMap<Int, Int>()
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (!isVideo && !isAudio) continue
                    if (mime != "video/avc" && mime != "video/hevc" && mime != "audio/mp4a-latm") return null
                    ex.selectTrack(i)
                    map[i] = m.addTrack(f)
                }
                maps.add(map)
            }
            if (maps.all { it.isEmpty() }) return null

            val firsts = exs.map { it.sampleTime }.filter { it >= 0 }
            if (firsts.isEmpty()) return null
            val base = firsts.minOrNull() ?: return null

            m.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                // 시간이 가장 이른 샘플을 가진 입력부터 읽어 영상/소리를 시간순으로 섞어 쓴다
                var k = -1
                var kt = Long.MAX_VALUE
                for (j in exs.indices) {
                    val t = exs[j].sampleTime
                    if (t in 0 until kt) {
                        kt = t
                        k = j
                    }
                }
                if (k < 0) break
                val ex = exs[k]
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val track = maps[k][ex.sampleTrackIndex]
                if (track != null) {
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, maxOf(0L, kt - base), flags)
                    m.writeSampleData(track, buf, info)
                }
                ex.advance()
            }
            m.stop()
            return maxOf(0L, base)
        } catch (e: Exception) {
            return null
        } finally {
            exs.forEach {
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            try {
                mux?.release()
            } catch (_: Exception) {
            }
        }
    }
}
