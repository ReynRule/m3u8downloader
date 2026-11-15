package com.m3u8.downloader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/** 내려받은 자막 1개. [mapped] 이면 cue 시간이 미디어 절대 시간(HLS WebVTT X-TIMESTAMP-MAP)이다. */
class LoadedSub(val track: SubTrack, val lang: String, val name: String, val cues: List<Cue>, val mapped: Boolean) {
    /** 영상 시작(baseMs)을 0 으로 맞춘 cue */
    fun shifted(baseMs: Long): List<Cue> = SubtitleParser.shift(cues, if (mapped) -baseMs else 0L)
}

/** 사이트 자막(.vtt/.srt/.ass, HLS 자막 재생목록)을 받아서 cue 목록으로 만든다 */
object SubtitleFetcher {
    private const val MAX_TRACKS = 8
    private const val MAX_SEGMENTS = 6000

    fun candidates(item: VideoItem): List<SubTrack> =
        (item.subs + Detector.subsForPage(item.pageUrl)).distinctBy { it.url }.take(MAX_TRACKS)

    /** 실패한 자막은 건너뛴다 (영상 다운로드 자체는 실패시키지 않음) */
    suspend fun collect(item: VideoItem): List<LoadedSub> {
        val out = ArrayList<LoadedSub>()
        for (t in candidates(item)) {
            try {
                val l = load(t, item)
                // 같은 자막이 두 경로(마스터 + 페이지)로 잡힌 경우 한 번만
                if (l.cues.isNotEmpty() && out.none { it.cues == l.cues }) out.add(l)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 이 자막만 건너뜀
            }
        }
        return out
    }

    private suspend fun load(t: SubTrack, item: VideoItem): LoadedSub {
        val text = SubtitleParser.decode(Downloader.fetchBytes(t.url, item, 0, -1))
        val cues: List<Cue>
        var mapped = false
        if (text.trimStart().startsWith("#EXTM3U")) {
            val segs = M3u8.parseMedia(text, t.url).segments.take(MAX_SEGMENTS)
            if (segs.isEmpty()) throw IOException("자막 조각이 없습니다")
            val sem = Semaphore(6)
            val parts = coroutineScope {
                segs.map { s ->
                    async {
                        sem.withPermit {
                            try {
                                SubtitleParser.parse(SubtitleParser.decode(Downloader.fetchBytes(s.url, item, s.rangeStart, s.rangeLen)))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            val ok = parts.filterNotNull()
            if (ok.isEmpty()) throw IOException("자막을 받지 못했습니다")
            mapped = ok.any { it.mapped }
            cues = SubtitleParser.normalize(ok.flatMap { it.cues })
        } else {
            val p = SubtitleParser.parse(text)
            cues = p.cues
            mapped = p.mapped
        }
        if (cues.isEmpty()) throw IOException("자막 내용이 비어 있습니다")
        val lang = t.lang.ifBlank { SubtitleParser.guessLangFromText(cues) }
        val name = t.name.ifBlank { lang }
        return LoadedSub(t, lang, name, cues, mapped)
    }

    /** MP4 에 넣을 트랙 목록. 기본 자막은 정확히 1개(HLS DEFAULT → 한국어 → 첫 번째) */
    fun toTracks(subs: List<LoadedSub>, baseMs: Long): List<TextTrack> {
        val defIdx = subs.indexOfFirst { it.track.isDefault }.takeIf { it >= 0 }
            ?: subs.indexOfFirst { it.lang.startsWith("ko") }.takeIf { it >= 0 }
            ?: 0
        return subs.mapIndexed { i, s -> TextTrack(s.lang, s.name, s.shifted(baseMs), i == defIdx) }
    }
}
