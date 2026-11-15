package com.m3u8.downloader

import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 브라우저 네트워크 요청에서 m3u8 을 감지 → 자동 분석(master 해석, 최고화질 선택,
 * 상대경로 -> 절대경로 병합) → 목록에 추가
 */
object Detector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val children: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // ---- 자막: 같은 페이지에서 감지된 자막 파일(.vtt/.srt/...)·자막 재생목록 후보
    private val pageSubs = ConcurrentHashMap<String, MutableList<SubTrack>>()
    private val subSegs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hlsSubPages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val staticExt = setOf(
        "js", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "ts", "m4s", "mp4",
        "html", "htm", "json", "woff", "woff2", "ttf", "map", "txt", "aac", "mp3"
    )

    fun looksLikeM3u8(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (!lower.contains("m3u8")) return false
        val path = lower.substringBefore('#').substringBefore('?')
        if (path.endsWith(".m3u8")) return true
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return ext !in staticExt
    }

    /** 페이지에서 감지된 자막 후보 (다운로드 직전에 한 번 더 합칠 때 사용) */
    fun subsForPage(pageUrl: String): List<SubTrack> =
        pageSubs[pageUrl]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    private fun addPageSub(pageUrl: String, t: SubTrack) {
        if (pageUrl.isBlank()) return
        if (pageSubs.size > 60) pageSubs.clear()
        val list = pageSubs.getOrPut(pageUrl) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(list) {
            if (list.any { it.url == t.url }) return
            if (list.size >= 12) return
            list.add(t)
        }
        // 이미 목록에 올라온 같은 페이지의 영상에도 붙인다
        Repo.items.value.filter {
            it.pageUrl == pageUrl && it.status != Status.DONE && it.subs.none { s -> s.url == t.url }
        }.forEach { v -> Repo.update(v.id) { cur -> cur.copy(subs = cur.subs + t) } }
    }

    private fun onSubUrl(url: String, pageUrl: String) {
        if (url in subSegs) return
        // 이 페이지가 HLS 자막 재생목록을 쓰면, 개별 .vtt 는 그 조각이므로 무시
        if (pageUrl in hlsSubPages) return
        addPageSub(pageUrl, SubTrack(url, guessLang(url), url.substringBefore('?').substringAfterLast('/')))
    }

    private fun guessLang(url: String): String {
        val name = url.lowercase().substringBefore('?')
        val re = Regex("(?<![a-z])(ko|kor|korean|kr|en|eng|english|ja|jpn|jp|japanese|zh|chi|chs|cht|chinese)(?![a-z])")
        val code = re.find(name.substringAfterLast('/'))?.groupValues?.get(1)
            ?: re.find(name)?.groupValues?.get(1) ?: return ""
        return when (code) {
            "ko", "kor", "korean", "kr" -> "ko"
            "en", "eng", "english" -> "en"
            "ja", "jpn", "jp", "japanese" -> "ja"
            else -> "zh"
        }
    }

    fun forget(urls: Collection<String>) {
        seen.removeAll(urls.toSet())
    }

    /** WebView 의 백그라운드 스레드에서 호출될 수 있음 */
    fun onUrl(url: String, pageUrl: String, referer: String, title: String, ua: String) {
        if (M3u8.isSubtitleFile(url)) {
            onSubUrl(url, pageUrl)
            return
        }
        if (!looksLikeM3u8(url)) return
        if (children.contains(url)) return
        if (Repo.items.value.any { it.sourceUrl == url }) {
            seen.add(url)
            return
        }
        if (!seen.add(url)) return

        val cookie = try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Throwable) {
            ""
        }
        val item = VideoItem(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { Http.hostOf(url).ifBlank { "video" } }.take(120),
            pageUrl = pageUrl,
            sourceUrl = url,
            userAgent = ua,
            cookie = cookie,
            referer = referer.ifBlank { pageUrl },
            status = Status.ANALYZING
        )
        Repo.add(item)
        scope.launch { analyze(item) }
    }

    private fun analyze(item: VideoItem) {
        try {
            var url = item.sourceUrl
            var text = Http.getText(url, item)
            if (!text.trimStart().startsWith("#EXTM3U")) {
                // m3u8 이 아닌 요청(오탐) -> 조용히 제거
                Repo.remove(listOf(item.id))
                return
            }

            var resolution = ""
            var declaredSubs: List<SubTrack> = emptyList()
            val variantUrls = HashSet<String>()
            var guard = 0
            while (M3u8.isMaster(text) && guard++ < 3) {
                val variants = M3u8.parseMaster(text, url)
                val ms = M3u8.parseMasterSubtitles(text, url)
                if (ms.isNotEmpty()) {
                    declaredSubs = declaredSubs + ms
                    children.addAll(ms.map { it.url })
                    hlsSubPages.add(item.pageUrl)
                }
                if (variants.isEmpty()) throw IOException("화질 목록을 찾을 수 없습니다")
                variantUrls.addAll(variants.map { it.url })
                children.addAll(variants.map { it.url })
                val best = variants.maxWith(compareBy<M3u8.Variant>({ it.bandwidth }, { it.height }))
                resolution = best.label()
                url = best.url
                text = Http.getText(url, item)
                if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("잘못된 재생목록 응답")
            }

            val media = M3u8.parseMedia(text, url)
            if (media.segments.isEmpty()) throw IOException("영상 조각(segment)이 없습니다")

            // 영상이 아니라 자막 재생목록(.vtt 조각)이면 -> 목록에서 빼고 자막 후보로만 기록
            if (M3u8.isSubtitleMedia(media)) {
                Repo.remove(listOf(item.id))
                hlsSubPages.add(item.pageUrl)
                subSegs.addAll(media.segments.map { it.url })
                pageSubs[item.pageUrl]?.let { l -> synchronized(l) { l.removeAll { it.url in subSegs } } }
                addPageSub(item.pageUrl, SubTrack(url, guessLang(url), url.substringBefore('?').substringAfterLast('/')))
                return
            }
            val unsupported = media.segments.firstOrNull {
                it.keyMethod != null && it.keyMethod != "NONE" && it.keyMethod != "AES-128"
            }
            if (unsupported != null) throw IOException("지원하지 않는 암호화(${unsupported.keyMethod})")

            // master 에서 파생된 하위(variant) 항목이 먼저 잡혀 있었다면 정리
            if (variantUrls.isNotEmpty()) {
                val stale = Repo.items.value.filter {
                    it.id != item.id && it.sourceUrl in variantUrls && it.status == Status.READY
                }.map { it.id }
                Repo.remove(stale)
            }

            // 같은 영상이 이미 목록에 있으면 중복 제거
            val dup = Repo.items.value.any { it.id != item.id && it.mediaUrl == url }
            if (dup) {
                Repo.remove(listOf(item.id))
                return
            }

            // 병합(해석 완료)된 m3u8 저장
            Repo.playlistFile(item.id).writeText(M3u8.rewriteAbsolute(text, url))

            // master 가 알려준 자막(언어/이름 정보가 정확) 우선 + 페이지에서 따로 감지된 자막
            val declaredUrls = declaredSubs.map { it.url }.toSet()
            Repo.update(item.id) {
                val extra = (it.subs + subsForPage(item.pageUrl)).filter { s -> s.url !in declaredUrls }
                it.copy(
                    subs = (declaredSubs + extra).distinctBy { s -> s.url },
                    mediaUrl = url,
                    resolution = resolution,
                    durationSec = media.duration,
                    segmentCount = media.segments.size,
                    encrypted = media.segments.any { s -> s.keyMethod == "AES-128" && s.keyUri != null },
                    isLive = media.live,
                    fmp4 = media.fmp4,
                    status = Status.READY,
                    error = ""
                )
            }
            Repo.get(item.id)?.let { Repo.detected.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repo.update(item.id) {
                it.copy(status = Status.FAILED, error = e.message ?: "분석 실패")
            }
        }
    }
}
