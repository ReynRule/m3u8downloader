package com.m3u8.downloader

/**
 * 안드로이드/네트워크에 의존하지 않는 순수 함수 모음 (조각 앞부분 정리, ID3 타임스탬프, 영상-오디오 짝짓기).
 * 단위 테스트하기 쉽도록 따로 분리했다.
 */
object StreamUtil {
    /** 조각 앞부분을 읽어 판단할 때 쓰는 바이트 수 */
    const val HEAD_BYTES = 64 * 1024

    private const val APPLE_TS_OWNER = "com.apple.streaming.transportStreamTimestamp"

    /** 맨 앞에 연속으로 붙은 ID3v2 태그들의 총 길이(헤더+본문+푸터). ID3 로 시작하지 않으면 0 */
    fun id3Length(d: ByteArray): Int {
        var p = 0
        while (d.size - p >= 10 && d[p] == 0x49.toByte() && d[p + 1] == 0x44.toByte() && d[p + 2] == 0x33.toByte()) {
            val size = (d[p + 6].toInt() and 0x7f shl 21) or (d[p + 7].toInt() and 0x7f shl 14) or
                (d[p + 8].toInt() and 0x7f shl 7) or (d[p + 9].toInt() and 0x7f)
            val footer = if (d[p + 5].toInt() and 0x10 != 0) 10 else 0
            p += 10 + size + footer
        }
        return p
    }

    /**
     * 패킹된 오디오(.aac) 조각 맨 앞 ID3 의 PRIV(com.apple.streaming.transportStreamTimestamp) 값.
     * 이 조각의 첫 샘플 PTS (90kHz 단위, 33비트). 없으면 null.
     * 영상 TS 의 PTS 와 같은 시계라서, 소리와 영상의 시작 시각 차이를 맞추는 데 쓴다.
     */
    fun id3Pts90k(d: ByteArray): Long? {
        if (d.size < 10 || d[0] != 0x49.toByte() || d[1] != 0x44.toByte() || d[2] != 0x33.toByte()) return null
        val ver = d[3].toInt()
        val tagSize = (d[6].toInt() and 0x7f shl 21) or (d[7].toInt() and 0x7f shl 14) or
            (d[8].toInt() and 0x7f shl 7) or (d[9].toInt() and 0x7f)
        val end = minOf(d.size, 10 + tagSize)
        var p = 10
        while (p + 10 <= end) {
            if (d[p] == 0.toByte()) break // 패딩
            val id = String(d, p, 4, Charsets.ISO_8859_1)
            val fs = if (ver >= 4)
                (d[p + 4].toInt() and 0x7f shl 21) or (d[p + 5].toInt() and 0x7f shl 14) or
                    (d[p + 6].toInt() and 0x7f shl 7) or (d[p + 7].toInt() and 0x7f)
            else
                (d[p + 4].toInt() and 0xff shl 24) or (d[p + 5].toInt() and 0xff shl 16) or
                    (d[p + 6].toInt() and 0xff shl 8) or (d[p + 7].toInt() and 0xff)
            val body = p + 10
            if (fs < 0 || body + fs > end) break
            if (id == "PRIV") {
                val z = (body until body + fs).firstOrNull { d[it] == 0.toByte() }
                if (z != null) {
                    val owner = String(d, body, z - body, Charsets.ISO_8859_1)
                    val dataStart = z + 1
                    if (owner == APPLE_TS_OWNER && body + fs - dataStart >= 8) {
                        var v = 0L
                        for (i in 0 until 8) v = (v shl 8) or (d[dataStart + i].toLong() and 0xff)
                        return v and 0x1FFFFFFFFL
                    }
                }
            }
            p = body + fs
        }
        return null
    }

    /**
     * 조각 파일 앞에서 버려야 할 바이트 수.
     * - ID3 태그 (패킹된 오디오: 남겨 두면 이어 붙인 파일에서 두 번째 ID3 를 만나는 순간 소리가 끊김)
     * - 일부 사이트가 TS 앞에 붙이는 가짜 이미지 헤더 (0x47 동기 바이트 3연속 위치부터 사용)
     * [head] 는 파일 앞부분(최대 [HEAD_BYTES]), [total] 은 파일 전체 크기.
     */
    fun headSkip(head: ByteArray, total: Long): Int {
        val p = minOf(id3Length(head), head.size)
        if (total - p < 564 || p >= head.size) return p
        val b0 = head[p]
        if (b0 == 0x47.toByte()) return p
        // 오디오(ID3 / ADTS)로 시작하는 조각은 건드리지 않음
        if (b0 == 0x49.toByte() && head.size > p + 2 && head[p + 1] == 0x44.toByte() && head[p + 2] == 0x33.toByte()) return p
        if (b0 == 0xFF.toByte() && p + 1 < head.size && (head[p + 1].toInt() and 0xF0) == 0xF0) return p
        val remain = minOf(total - p, (head.size - p).toLong()).toInt()
        val limit = minOf(remain - 376, 8192)
        for (k in 1 until limit) {
            if (head[p + k] == 0x47.toByte() && head[p + k + 188] == 0x47.toByte() && head[p + k + 376] == 0x47.toByte()) {
                return p + k
            }
        }
        return p
    }

    /** 두 재생목록 길이가 같은 영상의 영상/소리로 볼 만큼 비슷한지 (길이를 모르면 true) */
    fun durationClose(a: Double, b: Double): Boolean {
        if (a <= 0.0 || b <= 0.0) return true
        return Math.abs(a - b) <= maxOf(5.0, 0.05 * maxOf(a, b))
    }

    /** [target] 길이와 가장 가까우면서 허용 범위 안인 후보 (없으면 null). 길이를 모르는(0) 후보는 가장 뒤로 */
    fun <T> closestByDuration(target: Double, items: List<T>, dur: (T) -> Double): T? =
        items.filter { durationClose(target, dur(it)) }
            .minByOrNull { if (target <= 0.0 || dur(it) <= 0.0) Double.MAX_VALUE / 2 else Math.abs(dur(it) - target) }
}
