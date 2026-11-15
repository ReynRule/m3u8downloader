package com.m3u8.downloader

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 영상 목록 저장소 (메모리 + SharedPreferences 영속화) */
object Repo {
    private lateinit var prefs: SharedPreferences
    lateinit var appContext: Context
        private set

    private val lock = Any()
    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()

    /** 새 영상이 분석 완료되어 목록에 들어갔을 때 */
    val detected = MutableSharedFlow<VideoItem>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    var convertMp4: Boolean
        get() = prefs.getBoolean("convert_mp4", true)
        set(v) {
            prefs.edit().putBoolean("convert_mp4", v).apply()
        }

    /** 사이트 자막을 영상(MP4)에 합치기 */
    var mergeSubs: Boolean
        get() = prefs.getBoolean("merge_subs", true)
        set(v) {
            prefs.edit().putBoolean("merge_subs", v).apply()
        }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = appContext.getSharedPreferences("m3u8", Context.MODE_PRIVATE)
        val loaded = try {
            parse(prefs.getString("items", "[]") ?: "[]")
        } catch (e: Exception) {
            emptyList()
        }
        _items.value = loaded.map {
            when (it.status) {
                Status.ANALYZING -> it.copy(status = Status.FAILED, error = "분석이 중단되었습니다")
                Status.QUEUED, Status.DOWNLOADING, Status.MERGING ->
                    it.copy(status = Status.FAILED, error = "중단됨 - 다시 다운로드하면 이어받습니다")
                else -> it
            }
        }
    }

    fun get(id: String): VideoItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: VideoItem) {
        synchronized(lock) {
            _items.value = _items.value + item
            save()
        }
    }

    fun update(id: String, persist: Boolean = true, f: (VideoItem) -> VideoItem) {
        synchronized(lock) {
            val cur = _items.value
            val idx = cur.indexOfFirst { it.id == id }
            if (idx < 0) return
            val next = cur.toMutableList()
            next[idx] = f(cur[idx])
            _items.value = next
            if (persist) save()
        }
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        synchronized(lock) {
            _items.value = _items.value.filter { it.id !in set }
            save()
        }
        set.forEach { playlistFile(it).delete() }
    }

    fun playlistFile(id: String): File {
        val dir = File(appContext.filesDir, "playlists")
        dir.mkdirs()
        return File(dir, "$id.m3u8")
    }

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun parse(s: String): List<VideoItem> {
        val arr = JSONArray(s)
        val out = ArrayList<VideoItem>()
        for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
        return out
    }

    private fun toJson(v: VideoItem) = JSONObject().apply {
        put("id", v.id)
        put("title", v.title)
        put("pageUrl", v.pageUrl)
        put("sourceUrl", v.sourceUrl)
        put("mediaUrl", v.mediaUrl)
        put("userAgent", v.userAgent)
        put("cookie", v.cookie)
        put("referer", v.referer)
        put("resolution", v.resolution)
        put("durationSec", v.durationSec)
        put("segmentCount", v.segmentCount)
        put("encrypted", v.encrypted)
        put("isLive", v.isLive)
        put("fmp4", v.fmp4)
        put("subInfo", v.subInfo)
        put("subs", JSONArray().apply {
            v.subs.forEach {
                put(JSONObject().apply {
                    put("url", it.url); put("lang", it.lang); put("name", it.name); put("def", it.isDefault)
                })
            }
        })
        put("status", v.status.name)
        put("progress", v.progress)
        put("outputUri", v.outputUri)
        put("outputName", v.outputName)
        put("error", v.error)
        put("createdAt", v.createdAt)
    }

    private fun fromJson(o: JSONObject) = VideoItem(
        id = o.getString("id"),
        title = o.optString("title"),
        pageUrl = o.optString("pageUrl"),
        sourceUrl = o.optString("sourceUrl"),
        mediaUrl = o.optString("mediaUrl"),
        userAgent = o.optString("userAgent"),
        cookie = o.optString("cookie"),
        referer = o.optString("referer"),
        resolution = o.optString("resolution"),
        durationSec = o.optDouble("durationSec", 0.0),
        segmentCount = o.optInt("segmentCount"),
        encrypted = o.optBoolean("encrypted"),
        isLive = o.optBoolean("isLive"),
        fmp4 = o.optBoolean("fmp4"),
        subInfo = o.optString("subInfo"),
        subs = o.optJSONArray("subs")?.let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                SubTrack(j.optString("url"), j.optString("lang"), j.optString("name"), j.optBoolean("def"))
            }
        } ?: emptyList(),
        status = try {
            Status.valueOf(o.optString("status"))
        } catch (e: Exception) {
            Status.FAILED
        },
        progress = o.optInt("progress"),
        outputUri = o.optString("outputUri"),
        outputName = o.optString("outputName"),
        error = o.optString("error"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis())
    )
}
