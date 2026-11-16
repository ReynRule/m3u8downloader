package com.m3u8.downloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.m3u8.downloader.databinding.ActivityLogBinding

/** 디버그 로그 보기 / 복사 / 공유 / 지우기 */
class LogActivity : AppCompatActivity() {
    private lateinit var b: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnCopy.setOnClickListener {
            val all = Dbg.dump()
            // 클립보드는 바인더 한도(~1MB)가 있어 끝부분만 복사
            val cut = all.length > CLIP_MAX
            val text = if (cut) all.takeLast(CLIP_MAX) else all
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("m3u8 downloader log", text))
            Toast.makeText(
                this,
                if (cut) "최근 로그 ${CLIP_MAX / 1000}K자만 복사됨 (전체는 공유 사용)" else "로그 복사됨",
                Toast.LENGTH_SHORT
            ).show()
        }
        b.btnShare.setOnClickListener {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "m3u8 downloader log")
                        .putExtra(Intent.EXTRA_TEXT, Dbg.dump().takeLast(CLIP_MAX)),
                    "로그 공유"
                )
            )
        }
        b.btnClear.setOnClickListener {
            Dbg.clear()
            Dbg.i("Log", "로그 지움")
            render()
        }
        b.btnRefresh.setOnClickListener { render() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val all = Dbg.dump()
        // 화면에는 끝부분만 (TextView 가 수 MB 텍스트를 그리면 매우 느림)
        b.tv.text = if (all.length > VIEW_MAX) "…(앞부분 생략, 전체는 공유)\n" + all.takeLast(VIEW_MAX) else all.ifEmpty { "(로그 없음)" }
        b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    companion object {
        private const val CLIP_MAX = 300_000
        private const val VIEW_MAX = 200_000
    }
}
