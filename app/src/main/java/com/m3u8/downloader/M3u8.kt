package com.m3u8.downloader

import okhttp3.HttpUrl.Companion.toHttpUrl

/** HLS(m3u8) 재생목록 파서 */
object M3u8 {

    data class Variant(val url: String, val bandwidth: Long, val width: Int, val height: Int) {
        fun label(): String = when {
            height > 0 -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000}kbps"
            else -> ""
        }
    }

    class Segment(
        val url: String,
        val duration: Double,
        val seq: Long,
        val keyMethod: String?,
        val keyUri: String?,
        val iv: String?,
        val rangeStart: Long,
        val rangeLen: Long
    )

    class Media(val segments: List<Segment>, val mapUrl: String?, val live: Boolean) {
        val duration: Double get() = segments.sumOf { it.duration }
        val fmp4: Boolean get() = mapUrl != null
    }

    private val attrRe = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun attrs(s: String): Map<String, String> =
        attrRe.findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim().trim('"') }

    fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            base.toHttpUrl().resolve(ref)?.toString() ?: ref
        } catch (e: Exception) {
            ref
        }
    }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun parseMaster(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = attrs(line.substringAfter(':'))
            } else if (!line.startsWith("#")) {
                val p = pending
                if (p != null) {
                    val res = p["RESOLUTION"]?.split('x', 'X')
                    val w = res?.getOrNull(0)?.toIntOrNull() ?: 0
                    val h = res?.getOrNull(1)?.toIntOrNull() ?: 0
                    val bw = p["BANDWIDTH"]?.toLongOrNull() ?: p["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                    out.add(Variant(resolve(base, line), bw, w, h))
                    pending = null
                }
            }
        }
        return out
    }

    /** master 재생목록의 자막 트랙(#EXT-X-MEDIA:TYPE=SUBTITLES). 강제 자막(FORCED=YES)은 제외 */
    fun parseMasterSubtitles(text: String, base: String): List<SubTrack> {
        val out = ArrayList<SubTrack>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val a = attrs(line.substringAfter(':'))
            if (a["TYPE"] != "SUBTITLES") continue
            if (a["FORCED"] == "YES") continue
            val uri = a["URI"] ?: continue
            out.add(
                SubTrack(
                    url = resolve(base, uri),
                    lang = a["LANGUAGE"] ?: "",
                    name = a["NAME"] ?: "",
                    isDefault = a["DEFAULT"] == "YES"
                )
            )
        }
        return out.distinctBy { it.url }
    }

    /** 조각이 모두 자막 파일(.vtt 등)이면 영상이 아니라 자막 재생목록 */
    fun isSubtitleMedia(media: Media): Boolean =
        media.segments.isNotEmpty() && media.segments.all { isSubtitleFile(it.url) }

    fun isSubtitleFile(url: String): Boolean {
        val path = url.lowercase().substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return ext in setOf("vtt", "webvtt", "srt", "ass", "ssa")
    }

    fun parseMedia(text: String, base: String): Media {
        var seq = 0L
        var method: String? = null
        var keyUri: String? = null
        var iv: String? = null
        var dur = 0.0
        var rStart = 0L
        var rLen = -1L
        var lastEnd = 0L
        var mapUrl: String? = null
        var ended = false
        var index = 0
        val segs = ArrayList<Segment>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                    line.startsWith("#EXTINF:") ->
                        dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = attrs(line.substringAfter(':'))
                        method = a["METHOD"]
                        if (method == null || method == "NONE") {
                            keyUri = null
                            iv = null
                        } else {
                            keyUri = a["URI"]?.let { resolve(base, it) }
                            iv = a["IV"]
                        }
                    }

                    line.startsWith("#EXT-X-MAP:") -> {
                        if (mapUrl == null) {
                            val u = attrs(line.substringAfter(':'))["URI"]
                            if (u != null) mapUrl = resolve(base, u)
                        }
                    }

                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        val v = line.substringAfter(':').trim()
                        val len = v.substringBefore('@').toLongOrNull() ?: -1L
                        val off = if (v.contains('@')) (v.substringAfter('@').toLongOrNull() ?: lastEnd) else lastEnd
                        rLen = len
                        rStart = off
                        if (len > 0) lastEnd = off + len
                    }

                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                }
            } else {
                segs.add(Segment(resolve(base, line), dur, seq + index, method, keyUri, iv, rStart, rLen))
                index++
                dur = 0.0
                rLen = -1L
                rStart = 0L
            }
        }
        return Media(segs, mapUrl, !ended)
    }

    /** 상대경로를 모두 절대경로로 바꾼 "병합된(해석 완료된)" 재생목록 텍스트 */
    fun rewriteAbsolute(text: String, base: String): String {
        val uriRe = Regex("URI=\"([^\"]*)\"")
        return text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> ""
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:") ->
                    uriRe.replace(line) { m -> "URI=\"" + resolve(base, m.groupValues[1]) + "\"" }
                line.startsWith("#") -> line
                else -> resolve(base, line)
            }
        }
    }
}
