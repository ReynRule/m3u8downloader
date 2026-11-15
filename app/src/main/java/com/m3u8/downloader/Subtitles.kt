package com.m3u8.downloader

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 자막 한 줄(시간은 ms) */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** 파싱 결과. [mapped] = WebVTT X-TIMESTAMP-MAP 이 있어 cue 시간이 "미디어(MPEG-TS) 절대 시간"으로 보정된 경우 */
class ParsedSubs(val cues: List<Cue>, val mapped: Boolean)

/** SRT / WebVTT(HLS 조각 포함) / ASS·SSA 파서 (Android 의존성 없음) */
object SubtitleParser {

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (_: CharacterCodingException) {
        }
        // 오래된 한국 사이트 자막(EUC-KR / CP949)
        for (name in listOf("x-windows-949", "EUC-KR")) {
            try {
                return String(bytes, Charset.forName(name))
            } catch (_: Exception) {
            }
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** 내용을 보고 형식을 판별해서 파싱 */
    fun parse(raw: String): ParsedSubs {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("(?m)^[ \\t]+$"), "")
        val head = text.trimStart()
        return when {
            head.startsWith("WEBVTT") -> parseVtt(text)
            head.startsWith("[Script Info]") || head.startsWith("[V4") ||
                Regex("(?m)^Dialogue:").containsMatchIn(text) -> ParsedSubs(normalize(parseAss(text)), false)
            else -> ParsedSubs(normalize(parseSrt(text)), false)
        }
    }

    // ---------------------------------------------------------------- 시간
    private val timeRe = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?""")

    fun parseTime(s: String): Long? {
        val m = timeRe.matchEntire(s.trim()) ?: return null
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val mi = m.groupValues[2].toLong()
        val se = m.groupValues[3].toLong()
        val msStr = m.groupValues[4].padEnd(3, '0')
        val ms = if (msStr.isEmpty()) 0L else msStr.toLong()
        return ((h * 60 + mi) * 60 + se) * 1000 + ms
    }

    private fun timingLine(line: String): Pair<Long, Long>? {
        val idx = line.indexOf("-->")
        if (idx < 0) return null
        val a = parseTime(line.substring(0, idx)) ?: return null
        val rest = line.substring(idx + 3).trim().substringBefore(' ')
        val b = parseTime(rest) ?: return null
        return a to b
    }

    // ---------------------------------------------------------------- SRT / VTT
    private fun parseSrt(text: String): List<Cue> = parseBlocks(text, 0L)

    fun parseVtt(text: String): ParsedSubs {
        var offset = 0L
        var mapped = false
        val m = Regex("X-TIMESTAMP-MAP=([^\\n]+)").find(text)
        if (m != null) {
            val body = m.groupValues[1]
            val mpegts = Regex("MPEGTS:(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
            val local = Regex("LOCAL:([0-9:.,]+)").find(body)?.groupValues?.get(1)?.let { parseTime(it) } ?: 0L
            if (mpegts != null) {
                offset = mpegts * 1000L / 90000L - local
                mapped = true
            }
        }
        return ParsedSubs(normalize(parseBlocks(text, offset)), mapped)
    }

    private fun parseBlocks(text: String, offset: Long): List<Cue> {
        val out = ArrayList<Cue>()
        for (block in text.split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            var ti = -1
            for (i in lines.indices) {
                if (lines[i].contains("-->")) {
                    ti = i
                    break
                }
            }
            if (ti < 0) continue
            val first = lines[0].trim()
            if (first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val t = timingLine(lines[ti]) ?: continue
            val body = clean(lines.drop(ti + 1).joinToString("\n"))
            if (body.isEmpty()) continue
            out.add(Cue(t.first + offset, t.second + offset, body))
        }
        return out
    }

    // ---------------------------------------------------------------- ASS / SSA
    private fun parseAss(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var fields = 10
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.startsWith("Format:") && line.contains("Text")) {
                val f = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                if ("start" in f && "end" in f && "text" in f) {
                    startIdx = f.indexOf("start")
                    endIdx = f.indexOf("end")
                    textIdx = f.indexOf("text")
                    fields = f.size
                }
            } else if (line.startsWith("Dialogue:")) {
                val parts = line.substringAfter(':').trimStart().split(',', limit = fields)
                if (parts.size <= textIdx) continue
                val s = parseTime(parts[startIdx]) ?: continue
                val e = parseTime(parts[endIdx]) ?: continue
                val rawText = parts[textIdx]
                if (rawText.contains("\\p1")) continue // 벡터 그리기 명령
                val body = clean(
                    rawText.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                )
                if (body.isNotEmpty()) out.add(Cue(s, e, body))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 정리
    private val tagRe = Regex("<[^>]*>")
    private val assTagRe = Regex("\\{[^}]*\\}")

    private fun clean(s: String): String {
        var t = assTagRe.replace(s, "")
        t = tagRe.replace(t, "")
        t = t.replace("&nbsp;", " ").replace("&lrm;", "").replace("&rlm;", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        return t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** 빈 줄/잘못된 시간 제거 + 중복 제거 + 정렬 (HLS 조각 경계에서 반복되는 cue 처리) */
    fun normalize(cues: List<Cue>): List<Cue> =
        cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))

    /** 겹치는 cue 를 시간 구간별로 펼쳐서 겹치지 않는 목록으로 만든다 (MP4 텍스트 트랙은 겹침 불가) */
    fun flatten(cues: List<Cue>): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val sorted = cues.sortedBy { it.startMs }
        val out = ArrayList<Cue>()
        val active = ArrayList<Cue>()
        var idx = 0
        var t = sorted[0].startMs
        while (idx < sorted.size || active.isNotEmpty()) {
            if (active.isEmpty() && idx < sorted.size && sorted[idx].startMs > t) t = sorted[idx].startMs
            while (idx < sorted.size && sorted[idx].startMs <= t) active.add(sorted[idx++])
            val nextEnd = active.minOf { it.endMs }
            val nextStart = if (idx < sorted.size) sorted[idx].startMs else Long.MAX_VALUE
            val next = minOf(nextEnd, nextStart)
            if (next > t) {
                val text = active.map { it.text }.distinct().joinToString("\n")
                val last = out.lastOrNull()
                if (last != null && last.endMs == t && last.text == text) {
                    out[out.size - 1] = Cue(last.startMs, next, text)
                } else {
                    out.add(Cue(t, next, text))
                }
            }
            t = next
            active.removeAll { it.endMs <= t }
        }
        return out
    }

    /** cue 시간을 [deltaMs] 만큼 옮기고 0 미만은 잘라낸다 */
    fun shift(cues: List<Cue>, deltaMs: Long): List<Cue> {
        if (deltaMs == 0L) return cues
        return cues.mapNotNull {
            val s = it.startMs + deltaMs
            val e = it.endMs + deltaMs
            if (e <= 0) null else Cue(maxOf(0L, s), e, it.text)
        }
    }

    fun toSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            sb.append(i + 1).append('\n')
            sb.append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append('\n')
            sb.append(c.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    // ---------------------------------------------------------------- 언어 추정
    /** 언어 정보가 없을 때 자막 글자로 한국어/일본어/중국어를 추정 (못 찾으면 "") */
    fun guessLangFromText(cues: List<Cue>): String {
        var hangul = 0
        var kana = 0
        var han = 0
        var total = 0
        for (c in cues.take(300)) for (ch in c.text) {
            if (ch.isWhitespace()) continue
            total++
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> hangul++
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                else -> {}
            }
        }
        if (total == 0) return ""
        return when {
            hangul * 10 >= total * 2 -> "ko"
            kana * 10 >= total -> "ja"
            han * 10 >= total * 3 -> "zh"
            else -> ""
        }
    }
}
