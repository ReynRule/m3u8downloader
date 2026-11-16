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
        if (pageSubs[pageUrl]?.any { it.url == url } != true) Dbg.i("Detect", "자막 파일 감지 $url")
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
        Dbg.i("Detect", "m3u8 후보 감지 $url (page=$pageUrl, referer=$referer)")

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


    private val pairLock = Any()

    /**
     * 사이트가 영상 재생목록과 오디오 재생목록을 따로 불러오는 경우(master 없이 각각 요청),
     * 감지된 항목이 영상 따로/소리 따로 두 개로 나뉘어 영상만 받으면 무음이 된다.
     * 같은 페이지에서 감지된 "소리 없는 영상" 과 "오디오 전용" 재생목록을 길이가 비슷한 것끼리 묶는다.
     */
    private fun tryPair(newId: String) {
      synchronized(pairLock) {
        val me = Repo.get(newId) ?: return@synchronized
        if (me.status != Status.READY) return@synchronized
        val all = Repo.items.value
        fun pairable(o: VideoItem) =
            o.id != me.id && o.pageUrl == me.pageUrl && (o.status == Status.READY || o.status == Status.FAILED)
        if (me.audioOnly) {
            val v = StreamUtil.closestByDuration(
                me.durationSec,
                all.filter { pairable(it) && !it.audioOnly && it.noAudio && it.audioUrl.isBlank() && it.mediaUrl.isNotBlank() }
            ) { it.durationSec } ?: return@synchronized
            attachAudio(v, me)
        } else if (me.noAudio && me.audioUrl.isBlank()) {
            val a = StreamUtil.closestByDuration(
                me.durationSec,
                all.filter { pairable(it) && it.audioOnly && it.status == Status.READY }
            ) { it.durationSec } ?: return@synchronized
            attachAudio(me, a)
        }
      }
    }

    private fun attachAudio(video: VideoItem, audio: VideoItem) {
        try {
            val src = Repo.playlistFile(audio.id)
            if (!src.exists()) return
            src.copyTo(Repo.audioPlaylistFile(video.id), overwrite = true)
        } catch (e: Exception) {
            Dbg.e("Detect", "오디오 재생목록 복사 실패", e)
            return
        }
        Dbg.i("Detect", "영상+오디오 짝 맞춤: '${video.title}' ${video.mediaUrl} + 오디오 ${audio.mediaUrl} (${video.durationSec.toInt()}s/${audio.durationSec.toInt()}s)")
        children.add(audio.sourceUrl)
        children.add(audio.mediaUrl)
        Repo.update(video.id) {
            it.copy(audioUrl = audio.mediaUrl, noAudio = false, status = Status.READY, error = "", progress = 0)
        }
        Repo.remove(listOf(audio.id))
    }

    private suspend fun analyze(item: VideoItem) {
        try {
            var url = item.sourceUrl
            var text = Http.getText(url, item)
            if (!text.trimStart().startsWith("#EXTM3U")) {
                // m3u8 이 아닌 요청(오탐) -> 조용히 제거
                Dbg.i("Detect", "m3u8 아님(오탐) 제거: ${text.take(40).replace('\n', ' ')}")
                Repo.remove(listOf(item.id))
                return
            }

            var resolution = ""
            var declaredSubs: List<SubTrack> = emptyList()
            val variantUrls = HashSet<String>()
            var audio: M3u8.AudioRendition? = null
            var guard = 0
            while (M3u8.isMaster(text) && guard++ < 3) {
                val variants = M3u8.parseMaster(text, url)
                Dbg.i("Detect", "master $url variant ${variants.size}개")
                variants.forEach { v ->
                    Dbg.i("Detect", "  ${v.label()} bw=${v.bandwidth} codecs='${v.codecs}' audio='${v.audioGroup}' aac=${v.aacAudio}")
                }
                text.lineSequence().filter { it.startsWith("#EXT-X-MEDIA:") }.forEach { Dbg.i("Detect", "  $it") }
                val ms = M3u8.parseMasterSubtitles(text, url)
                if (ms.isNotEmpty()) {
                    declaredSubs = declaredSubs + ms
                    children.addAll(ms.map { it.url })
                    hlsSubPages.add(item.pageUrl)
                }
                if (variants.isEmpty()) throw IOException("화질 목록을 찾을 수 없습니다")
                variantUrls.addAll(variants.map { it.url })
                children.addAll(variants.map { it.url })
                // 같은 화질이 AAC / AC-3·E-AC-3 소리로 나뉘어 있으면 AAC 쪽을 고른다
                // (AC-3 계열은 MP4 로 합칠 수 없어 실패하거나 소리 없는 파일이 됨)
                val videoVariants = variants.filter { !it.audioOnly }.ifEmpty { variants }
                val best = videoVariants.maxWith(compareBy<M3u8.Variant>({ it.aacAudio }, { it.bandwidth }, { it.height }))
                resolution = best.label()
                Dbg.i("Detect", "선택 variant ${best.label()} bw=${best.bandwidth} codecs='${best.codecs}' ${best.url}")
                // 소리가 영상과 따로 있는 경우(별도 오디오 트랙) 같이 받을 수 있게 기록
                var pa = M3u8.pickAudio(text, url, best)
                if (pa == null) {
                    // EXT-X-MEDIA 없이 master 가 "소리 전용 variant" 를 따로 나열하는 경우
                    val av = M3u8.pickAudioVariant(variants, best)
                    if (av != null) {
                        pa = M3u8.AudioRendition(av.url, "", "audio-variant ${av.bandwidth / 1000}kbps", true)
                        Dbg.i("Detect", "소리 전용 variant 를 오디오로 사용 bw=${av.bandwidth} codecs='${av.codecs}' ${av.url}")
                    }
                }
                val picked = pa
                Dbg.i("Detect", if (picked != null) "분리 오디오 선택 '${picked.name}' group=${picked.group} default=${picked.isDefault} ${picked.url}" else "분리 오디오 없음 (소리가 영상 조각 안에 있어야 함)")
                if (picked != null) {
                    audio = picked
                    variantUrls.add(picked.url)
                    children.add(picked.url)
                }
                url = best.url
                text = Http.getText(url, item)
                if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("잘못된 재생목록 응답")
            }

            val media = M3u8.parseMedia(text, url)
            Dbg.i("Detect", "media $url 조각 ${media.segments.size}개 ${media.duration.toInt()}s fmp4=${media.fmp4} map=${media.mapUrl} live=${media.live} key=${media.segments.firstOrNull()?.keyMethod} 첫조각=${media.segments.firstOrNull()?.url}")
            if (media.segments.isEmpty()) throw IOException("영상 조각(segment)이 없습니다")

            // 영상이 아니라 자막 재생목록(.vtt 조각)이면 -> 목록에서 빼고 자막 후보로만 기록
            if (M3u8.isSubtitleMedia(media)) {
                Dbg.i("Detect", "자막 재생목록이라 영상 목록에서 제외 $url")
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

            // 첫 조각을 받아 이 재생목록이 영상인지/소리만인지/영상인데 소리가 없는지 확인
            val probe = MediaProbe.probe(item, media)
            Dbg.i("Detect", "조각 분석 video=${probe?.video} audio=${probe?.audio} (null=확인 못 함)")
            if (probe != null && probe.video && probe.audio && audio != null) {
                // 영상 조각 안에 이미 소리가 있으면 따로 받을 필요가 없다 (합치면 소리 트랙이 2개가 됨)
                Dbg.i("Detect", "영상 조각에 소리 포함 -> 분리 오디오 무시")
                audio = null
            }
            val audioOnly = probe != null && probe.audio && !probe.video

            // 별도 오디오 재생목록 받아서 저장
            var audioUrl = ""
            audio?.let { a ->
                val at = Http.getText(a.url, item)
                if (!at.trimStart().startsWith("#EXTM3U")) throw IOException("잘못된 오디오 재생목록 응답")
                val am = M3u8.parseMedia(at, a.url)
                Dbg.i("Detect", "오디오 media 조각 ${am.segments.size}개 fmp4=${am.fmp4} map=${am.mapUrl} key=${am.segments.firstOrNull()?.keyMethod} 첫조각=${am.segments.firstOrNull()?.url}")
                if (am.segments.isEmpty()) throw IOException("오디오 조각(segment)이 없습니다")
                val badAudio = am.segments.firstOrNull {
                    it.keyMethod != null && it.keyMethod != "NONE" && it.keyMethod != "AES-128"
                }
                if (badAudio != null) throw IOException("지원하지 않는 암호화(${badAudio.keyMethod})")
                Repo.audioPlaylistFile(item.id).writeText(M3u8.rewriteAbsolute(at, a.url))
                audioUrl = a.url
            }

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
                Dbg.i("Detect", "이미 목록에 있는 영상 -> 중복 제거")
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
                    audioUrl = audioUrl,
                    resolution = if (audioOnly) "오디오" else resolution,
                    durationSec = media.duration,
                    segmentCount = media.segments.size,
                    encrypted = media.segments.any { s -> s.keyMethod == "AES-128" && s.keyUri != null },
                    isLive = media.live,
                    fmp4 = media.fmp4,
                    audioOnly = audioOnly,
                    noAudio = probe != null && probe.video && !probe.audio && audioUrl.isBlank(),
                    status = Status.READY,
                    error = ""
                )
            }
            Dbg.i("Detect", "목록 추가 '${item.title}' $resolution audioUrl=${audioUrl.ifBlank { "-" }} audioOnly=$audioOnly")
            // 같은 페이지에서 따로 잡힌 영상/오디오 재생목록끼리 짝을 맞춘다 (짝이 되면 오디오 쪽 항목은 사라짐)
            tryPair(item.id)
            Repo.get(item.id)?.let { if (!it.audioOnly) Repo.detected.tryEmit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Dbg.e("Detect", "분석 실패 ${item.sourceUrl}", e)
            Repo.update(item.id) {
                it.copy(status = Status.FAILED, error = e.message ?: "분석 실패")
            }
        }
    }
}
