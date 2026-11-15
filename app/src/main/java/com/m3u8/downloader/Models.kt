package com.m3u8.downloader

enum class Status { ANALYZING, READY, QUEUED, DOWNLOADING, MERGING, DONE, FAILED }

/** 영상에 합칠 자막 1개. url 은 .vtt/.srt/.ass 파일 또는 자막 m3u8(HLS 자막 재생목록) */
data class SubTrack(
    val url: String,
    val lang: String = "",
    val name: String = "",
    val isDefault: Boolean = false
)

data class VideoItem(
    val id: String,
    val title: String,
    val pageUrl: String,
    val sourceUrl: String,
    val mediaUrl: String = "",
    val audioUrl: String = "",
    val userAgent: String = "",
    val cookie: String = "",
    val referer: String = "",
    val resolution: String = "",
    val durationSec: Double = 0.0,
    val segmentCount: Int = 0,
    val encrypted: Boolean = false,
    val isLive: Boolean = false,
    val fmp4: Boolean = false,
    val subs: List<SubTrack> = emptyList(),
    val subInfo: String = "",
    val status: Status = Status.ANALYZING,
    val progress: Int = 0,
    val outputUri: String = "",
    val outputName: String = "",
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
