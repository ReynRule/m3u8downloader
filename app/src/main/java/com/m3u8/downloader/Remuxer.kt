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
    fun mux(video: File, audio: File, dst: File, audioOffsetUs: Long = 0L): Long? =
        process(listOf(video, audio), dst, longArrayOf(0L, audioOffsetUs))

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

    /**
     * 패킹된 .aac(ADTS) 에서 MediaExtractor 가 샘플을 ADTS 헤더(7바이트, CRC 있으면 9바이트)째로 넘겨주는 경우가 있다.
     * MP4(esds/AudioSpecificConfig 기반)에는 헤더 없는 raw AAC 프레임만 들어가야 하므로 여기서 헤더를 떼어낸다.
     * VLC/ffmpeg 는 헤더가 남아 있어도 무시하지만 Windows 기본 플레이어, 안드로이드 갤러리, iOS 는 소리를 못 낸다.
     *
     * 오탐(raw 프레임이 우연히 0xFFF 로 시작) 방지를 위해 헤더의 frame_length 가 샘플 크기와 정확히 같을 때만 제거한다.
     * @return 제거할 헤더 크기 (헤더가 없으면 0)
     */
    private fun adtsHeaderSize(a: ByteArray, size: Int): Int {
        if (size < 7) return 0
        val b0 = a[0].toInt() and 0xFF
        val b1 = a[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return 0 // syncword 12bit + layer 00 (MPEG ver 은 무시)
        val hdr = if ((b1 and 0x01) == 0) 9 else 7 // protection_absent == 0 이면 CRC 2바이트 추가
        val frameLen = ((a[3].toInt() and 0x03) shl 11) or
            ((a[4].toInt() and 0xFF) shl 3) or
            ((a[5].toInt() and 0xE0) ushr 5)
        if (frameLen != size || size <= hdr) return 0
        return hdr
    }

    /** 성공하면 입력들의 첫 샘플 중 가장 이른 시간(마이크로초) = 출력에서 0 이 되는 기준 시간 */
    private fun process(srcs: List<File>, dst: File, offsets: LongArray = LongArray(srcs.size)): Long? {
        Dbg.i("Remux", "시작 ${srcs.joinToString(" + ") { "${it.name}(${it.length()}B)" }} -> ${dst.name}")
        val exs = ArrayList<MediaExtractor>()
        var mux: MediaMuxer? = null
        try {
            val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m

            val maps = ArrayList<HashMap<Int, Int>>()
            val aacTracks = HashSet<Int>() // 출력 MP4 의 AAC 오디오 트랙 번호 (ADTS 헤더 제거 대상)
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
                    if (mime == "audio/mp4a-latm") aacTracks.add(map[i]!!)
                }
                maps.add(map)
            }
            if (maps.all { it.isEmpty() }) {
                Dbg.e("Remux", "영상/오디오 트랙 없음 -> 실패")
                return null
            }
            maps.forEachIndexed { k, mp -> if (mp.isEmpty()) Dbg.e("Remux", "${srcs[k].name}: 쓸 트랙 없음") }

            val firstAll = exs.map { it.sampleTime }
            if (firstAll.none { it >= 0 }) {
                Dbg.e("Remux", "첫 샘플 없음 -> 실패")
                return null
            }
            // 패킹된 오디오(ADTS)는 시간이 0 부터 시작하므로, ID3 에 적힌 시작 PTS([offsets])만큼 밀어야 영상과 맞는다.
            // 다만 확장자/추출기에 따라 이미 시간이 맞춰진 경우도 있어서, 더하는 쪽이 영상 시작에 더 가까울 때만 적용한다.
            val eff = LongArray(srcs.size)
            for (j in srcs.indices) {
                val o = offsets.getOrElse(j) { 0L }
                val f = firstAll[j]
                if (o == 0L || f < 0) continue
                val ref = firstAll.filterIndexed { i, t -> i != j && t >= 0 }.minOrNull()
                eff[j] = if (ref == null || Math.abs(f + o - ref) <= Math.abs(f - ref)) o else 0L
                Dbg.i("Remux", "${srcs[j].name} 시작 보정 후보 ${o}us 첫샘플 ${f}us 기준 ${ref}us -> ${if (eff[j] != 0L) "적용" else "미적용"}")
            }
            val firsts = firstAll.mapIndexedNotNull { j, t -> if (t >= 0) t + eff[j] else null }
            val base = firsts.minOrNull() ?: return null
            Dbg.i("Remux", "첫 샘플 시간(us) ${firstAll.joinToString()} 보정 ${eff.joinToString()} base=$base")
            val counts = HashMap<Int, Int>()
            var adtsStripped = 0

            m.start()
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                // 시간이 가장 이른 샘플을 가진 입력부터 읽어 영상/소리를 시간순으로 섞어 쓴다
                var k = -1
                var kt = Long.MAX_VALUE
                for (j in exs.indices) {
                    val raw = exs[j].sampleTime
                    val t = if (raw >= 0) raw + eff[j] else -1L
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
                    var outSize = size
                    if (track in aacTracks && buf.hasArray()) {
                        val arr = buf.array()
                        val hdr = adtsHeaderSize(arr, size)
                        if (hdr > 0) {
                            System.arraycopy(arr, hdr, arr, 0, size - hdr)
                            outSize = size - hdr
                            adtsStripped++
                        }
                    }
                    buf.position(0)
                    buf.limit(outSize)
                    info.set(0, outSize, maxOf(0L, kt - base), flags)
                    m.writeSampleData(track, buf, info)
                    counts[track] = (counts[track] ?: 0) + 1
                }
                ex.advance()
            }
            m.stop()
            Dbg.i("Remux", "완료 ${dst.name} ${dst.length()}B, 출력 트랙별 샘플 수 $counts, ADTS 헤더 제거 $adtsStripped")
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
