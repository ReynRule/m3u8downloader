package com.m3u8.downloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.m3u8.downloader.databinding.FragmentListBinding
import kotlinx.coroutines.launch

class ListFragment : Fragment() {

    private var _b: FragmentListBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: VideoAdapter
    private val selected = LinkedHashSet<String>()
    private var current: List<VideoItem> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = VideoAdapter(
            onClick = { toggle(it.id) },
            onLong = { rename(it) },
            onOpen = { open(it) }
        )
        b.rv.layoutManager = LinearLayoutManager(requireContext())
        b.rv.adapter = adapter
        (b.rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        b.cbMp4.isChecked = Repo.convertMp4
        b.cbMp4.setOnCheckedChangeListener { _, c -> Repo.convertMp4 = c; Dbg.i("UI", "MP4 변환 = $c") }
        b.cbSubs.isChecked = Repo.mergeSubs
        b.cbSubs.setOnCheckedChangeListener { _, c -> Repo.mergeSubs = c; Dbg.i("UI", "자막 합치기 = $c") }

        b.cbAll.setOnClickListener {
            if (b.cbAll.isChecked) selected.addAll(current.map { it.id }) else selected.clear()
            adapter.selected = selected.toSet()
            adapter.notifyDataSetChanged()
            refreshHeader()
        }

        b.btnDownload.setOnClickListener { startDownload() }
        b.btnDelete.setOnClickListener { confirmDelete() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Repo.items.collect { render(it) }
            }
        }
    }

    private fun render(list: List<VideoItem>) {
        val bb = _b ?: return
        current = list.sortedByDescending { it.createdAt }
        selected.retainAll(current.map { it.id }.toSet())
        adapter.selected = selected.toSet()
        adapter.submitList(current) { refreshHeader() }
        bb.tvEmpty.visibility = if (current.isEmpty()) View.VISIBLE else View.GONE
        refreshHeader()
    }

    private fun toggle(id: String) {
        if (!selected.add(id)) selected.remove(id)
        adapter.selected = selected.toSet()
        val idx = adapter.currentList.indexOfFirst { it.id == id }
        if (idx >= 0) adapter.notifyItemChanged(idx)
        refreshHeader()
    }

    private fun refreshHeader() {
        val bb = _b ?: return
        val sel = current.filter { it.id in selected }
        bb.tvCount.text = "선택 ${sel.size} / 전체 ${current.size}"
        bb.cbAll.isChecked = current.isNotEmpty() && sel.size == current.size
        val dl = sel.count { it.status == Status.READY || it.status == Status.FAILED }
        bb.btnDownload.isEnabled = dl > 0
        bb.btnDownload.text = if (dl > 0) "선택 다운로드 ($dl)" else "선택 다운로드"
        bb.btnDelete.isEnabled = sel.isNotEmpty()
        bb.btnDelete.text = if (sel.isNotEmpty()) "선택 삭제 (${sel.size})" else "선택 삭제"
    }

    private fun startDownload() {
        val ids = current
            .filter { it.id in selected && (it.status == Status.READY || it.status == Status.FAILED) }
            .map { it.id }
        if (ids.isEmpty()) return
        Dbg.i("UI", "선택 다운로드 클릭: " + current.filter { it.id in ids }.joinToString { "'${it.title}'(${it.status})" })
        DownloadCenter.enqueue(requireContext(), ids)
        Toast.makeText(requireContext(), "${ids.size}개 다운로드를 시작합니다", Toast.LENGTH_SHORT).show()
        selected.clear()
        adapter.selected = emptySet()
        adapter.notifyDataSetChanged()
        refreshHeader()
    }

    private fun confirmDelete() {
        val ids = current.filter { it.id in selected }.map { it.id }
        if (ids.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("삭제")
            .setMessage("선택한 ${ids.size}개 항목을 목록에서 삭제할까요?\n(이미 저장된 영상 파일은 삭제되지 않습니다)")
            .setPositiveButton("삭제") { _, _ -> doDelete(ids) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun doDelete(ids: List<String>) {
        Dbg.i("UI", "선택 삭제 ${ids.size}개")
        val urls = current.filter { it.id in ids }.map { it.sourceUrl }
        Detector.forget(urls)
        DownloadCenter.cancelAndClean(ids)
        Repo.remove(ids)
        selected.removeAll(ids.toSet())
        refreshHeader()
    }

    private fun rename(item: VideoItem) {
        val ctx = requireContext()
        val et = EditText(ctx).apply {
            setText(item.title)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(ctx).apply {
            setPadding(pad, 8, pad, 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("이름 변경")
            .setView(box)
            .setPositiveButton("확인") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) Dbg.i("UI", "이름 변경 '${item.title}' -> '$t'")
                if (t.isNotEmpty()) Repo.update(item.id) { it.copy(title = t) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun open(item: VideoItem) {
        Dbg.i("UI", "재생 열기 ${item.outputName} ${item.outputUri}")
        val mime = if (item.outputName.endsWith(".ts")) "video/mp2t" else "video/mp4"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(item.outputUri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Dbg.e("UI", "재생 열기 실패", e)
            Toast.makeText(requireContext(), "재생할 수 있는 앱이 없거나 파일이 삭제되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
