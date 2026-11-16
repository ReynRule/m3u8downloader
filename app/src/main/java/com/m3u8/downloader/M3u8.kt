package com.m3u8.downloader

import okhttp3.HttpUrl.Companion.toHttpUrl

/** HLS(m3u8) 재생목록 파서 */
object M3u8 {

    data class Variant(
        val url: String, val bandwidth: Long, val width: Int, val height: Int,
        val audioGroup: String = "",
        val codecs: String = ""
    ) {
        /** MP4 로 합칠 수 있는 소리(AAC)인지. CODECS 에 AC-3/E-AC-3 등만 있으면 false */
        val aacAudio: Boolean
            get() {
                val c = codecs.lowercase()
                return "mp4a" in c || listOf("ac-3", "ec-3", "ac-4").none { it in c }
            }

        private val c get() = codecs.lowercase()

        /** CODECS 에 소리 코덱이 선언돼 있는지 */
        val hasAudioCodec: Boolean
            get() = listOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "mp3").any { it in c }

        /** CODECS 에 영상 코덱이 선언돼 있는지 */
        val hasVideoCodec: Boolean
            get() = listOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp09", "vp9", "dvh1", "dvhe").any { it in c }

        /** 소리만 있는 variant (master 가 영상 variant 와 오디오 variant 를 따로 나열하는 경우) */
        val audioOnly: Boolean
            get() = height == 0 && hasAudioCodec && !hasVideoCodec

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

    /** [mapStart]/[mapLen]: 초기화 세그먼트(EXT-X-MAP)의 BYTERANGE (없으면 파일 전체 = -1) */
    class Media(
        val segments: List<Segment>, val mapUrl: String?, val live: Boolean,
        val mapStart: Long = 0L, val mapLen: Long = -1L
    ) {
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
                    out.add(Variant(resolve(base, line), bw, w, h, p["AUDIO"] ?: "", p["CODECS"] ?: ""))
                    pending = null
                }
            }
        }
        return out
    }

    /** master 의 별도 오디오 트랙(#EXT-X-MEDIA:TYPE=AUDIO, URI 있는 것) */
    class AudioRendition(val url: String, val group: String, val name: String, val isDefault: Boolean)

    /**
     * [variant] 의 소리가 별도 파일로 제공될 때 그 오디오 하나를 고른다. 소리가 영상 안에 들어 있으면 null.
     * 1) AUDIO="그룹" 이 선언돼 있으면 그 그룹의 #EXT-X-MEDIA:TYPE=AUDIO (URI 가 없으면 영상 안에 포함)
     * 2) AUDIO 속성이 없는데 영상 variant 가 소리 코덱을 선언하지 않았고 오디오 MEDIA 만 따로 있으면 그것
     */
    fun pickAudio(text: String, base: String, variant: Variant): AudioRendition? {
        val all = ArrayList<AudioRendition>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val a = attrs(line.substringAfter(':'))
            if (a["TYPE"] != "AUDIO") continue
            val uri = a["URI"] ?: continue
            all.add(AudioRendition(resolve(base, uri), a["GROUP-ID"] ?: "", a["NAME"] ?: "", a["DEFAULT"] == "YES"))
        }
        val pool = when {
            variant.audioGroup.isNotBlank() -> all.filter { it.group == variant.audioGroup }
            variant.hasAudioCodec -> emptyList() // 소리가 영상에 들어 있다고 선언됨
            else -> all
        }
        return pool.firstOrNull { it.isDefault } ?: pool.firstOrNull()
    }

    /**
     * master 가 EXT-X-MEDIA 없이 "소리 전용 variant" 를 따로 나열하는 경우, [best] (영상 variant) 와 짝이 될
     * 소리 variant. AAC 우선, 그 안에서 비트레이트가 높은 것. [best] 가 이미 소리를 갖고 있으면 null.
     */
    fun pickAudioVariant(variants: List<Variant>, best: Variant): Variant? {
        if (best.audioOnly || best.hasAudioCodec) return null
        return variants.filter { it.audioOnly && it.url != best.url }
            .maxWithOrNull(compareBy<Variant>({ it.aacAudio }, { it.bandwidth }))
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
        var mapStart = 0L
        var mapLen = -1L
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
                            val a = attrs(line.substringAfter(':'))
                            val u = a["URI"]
                            if (u != null) {
                                mapUrl = resolve(base, u)
                                // 영상 전체가 한 파일(main.mp4)이고 init 은 그 앞부분 일부인 경우.
                                // 무시하면 init 대신 파일 전체를 한 연결로 받느라 매우 느리고 메모리 부족으로 실패함
                                a["BYTERANGE"]?.let { br ->
                                    mapLen = br.substringBefore('@').toLongOrNull() ?: -1L
                                    mapStart = br.substringAfter('@', "0").toLongOrNull() ?: 0L
                                }
                            }
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
        return Media(segs, mapUrl, !ended, mapStart, mapLen)
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
