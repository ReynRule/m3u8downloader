package com.m3u8.downloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.m3u8.downloader.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
    private val onLong: (VideoItem) -> Unit,
    private val onOpen: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VH>(DIFF) {

    var selected: Set<String> = emptySet()

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        val b = h.b

        b.cb.isChecked = item.id in selected
        b.tvTitle.text = item.title

        val meta = ArrayList<String>()
        if (item.resolution.isNotEmpty()) meta.add(item.resolution)
        if (item.durationSec > 0) meta.add(fmt(item.durationSec))
        if (item.segmentCount > 0) meta.add("${item.segmentCount}조각")
        if (item.encrypted) meta.add("AES-128")
        if (item.isLive) meta.add("LIVE")
        if (item.fmp4) meta.add("fMP4")
        if (item.audioOnly) meta.add("🔊 오디오 전용")
        else if (item.audioUrl.isNotEmpty()) meta.add("🔊 소리 연결됨")
        else if (item.noAudio) meta.add("🔇 소리 없음(오디오 대기)")
        if (item.subs.isNotEmpty()) meta.add("자막 ${item.subs.size}")
        b.tvMeta.text = if (meta.isEmpty()) hostOf(item) else meta.joinToString(" · ")

        val normal = MaterialColors.getColor(b.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        var color = normal
        b.tvStatus.text = when (item.status) {
            Status.ANALYZING -> "분석 중…"
            Status.READY -> "다운로드 대기"
            Status.QUEUED -> "대기열에 추가됨"
            Status.DOWNLOADING -> "다운로드 중 ${item.progress}%"
            Status.MERGING -> "병합 / 저장 중…"
            Status.DONE -> {
                color = 0xFF16A34A.toInt()
                "완료 · ${item.outputName}" + if (item.subInfo.isNotEmpty()) "\n${item.subInfo}" else ""
            }
            Status.FAILED -> {
                color = 0xFFDC2626.toInt()
                "실패: ${item.error}"
            }
        }
        b.tvStatus.setTextColor(color)
        b.tvMeta.setTextColor(normal)

        when (item.status) {
            Status.DOWNLOADING -> {
                b.pb.visibility = View.VISIBLE
                b.pb.isIndeterminate = false
                b.pb.progress = item.progress
            }
            Status.ANALYZING, Status.QUEUED, Status.MERGING -> {
                b.pb.visibility = View.VISIBLE
                b.pb.isIndeterminate = true
            }
            else -> b.pb.visibility = View.GONE
        }

        b.btnOpen.visibility = if (item.status == Status.DONE) View.VISIBLE else View.GONE
        b.btnOpen.setOnClickListener { onOpen(item) }
        b.root.setOnClickListener { onClick(item) }
        b.root.setOnLongClickListener {
            onLong(item)
            true
        }
    }

    private fun hostOf(item: VideoItem) = Http.hostOf(item.sourceUrl)

    private fun fmt(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
