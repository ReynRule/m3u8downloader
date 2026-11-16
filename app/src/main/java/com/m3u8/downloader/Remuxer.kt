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

    /** 파일의 트랙 목록 (로그용): mime / 샘플레이트·채널 / 해상도 / 길이 */
    fun describe(f: File): String {
        if (!f.exists()) return "${f.name}: 없음"
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            val tracks = (0 until ex.trackCount).map { fmtTrack(ex.getTrackFormat(it)) }
            "${f.name} (${f.length()}B) 트랙 ${ex.trackCount}개: " + tracks.joinToString(" | ")
        } catch (e: Exception) {
            "${f.name} (${f.length()}B) 읽기 실패: $e"
        } finally {
            ex.release()
        }
    }

    fun hasAudio(f: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.absolutePath)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (e: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    private fun fmtTrack(f: MediaFormat): String {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
        val sb = StringBuilder(mime)
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sb.append(" ${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)}Hz")
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) sb.append(" ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)}ch")
        if (f.containsKey(MediaFormat.KEY_WIDTH)) sb.append(" ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}")
        if (f.containsKey(MediaFormat.KEY_DURATION)) sb.append(" ${f.getLong(MediaFormat.KEY_DURATION) / 1000}ms")
        return sb.toString()
    }

    /** 성공하면 입력들의 첫 샘플 중 가장 이른 시간(마이크로초) = 출력에서 0 이 되는 기준 시간 */
    private fun process(srcs: List<File>, dst: File): Long? {
        Dbg.i("Remux", "시작 ${srcs.joinToString(" + ") { "${it.name}(${it.length()}B)" }} -> ${dst.name}")
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
                    Dbg.i("Remux", "  ${src.name} 트랙$i: ${fmtTrack(f)}")
                    if (!isVideo && !isAudio) continue
                    if (mime != "video/avc" && mime != "video/hevc" && mime != "audio/mp4a-latm") {
                        Dbg.e("Remux", "MP4 에 넣을 수 없는 코덱 $mime (${src.name}) -> 실패")
                        return null
                    }
                    ex.selectTrack(i)
                    map[i] = m.addTrack(f)
                }
                maps.add(map)
            }
            if (maps.all { it.isEmpty() }) {
                Dbg.e("Remux", "영상/오디오 트랙 없음 -> 실패")
                return null
            }
            maps.forEachIndexed { k, mp -> if (mp.isEmpty()) Dbg.e("Remux", "${srcs[k].name}: 쓸 트랙 없음") }

            val firstAll = exs.map { it.sampleTime }
            val firsts = firstAll.filter { it >= 0 }
            if (firsts.isEmpty()) {
                Dbg.e("Remux", "첫 샘플 없음 -> 실패")
                return null
            }
            val base = firsts.minOrNull() ?: return null
            Dbg.i("Remux", "첫 샘플 시간(us) ${firstAll.joinToString()} base=$base")
            val counts = HashMap<Int, Int>()

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
                    counts[track] = (counts[track] ?: 0) + 1
                }
                ex.advance()
            }
            m.stop()
            Dbg.i("Remux", "완료 ${dst.name} ${dst.length()}B, 출력 트랙별 샘플 수 $counts")
            return maxOf(0L, base)
        } catch (e: Exception) {
            Dbg.e("Remux", "예외로 실패", e)
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
