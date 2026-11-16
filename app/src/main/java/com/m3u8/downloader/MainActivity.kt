package com.m3u8.downloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.m3u8.downloader.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var browser: BrowserFragment
    private lateinit var listFrag: ListFragment
    private var tab = 0

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (savedInstanceState == null) {
            browser = BrowserFragment()
            listFrag = ListFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.container, browser, "browser")
                .add(R.id.container, listFrag, "list")
                .hide(listFrag)
                .commit()

            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            browser = supportFragmentManager.findFragmentByTag("browser") as BrowserFragment
            listFrag = supportFragmentManager.findFragmentByTag("list") as ListFragment
            tab = if (listFrag.isHidden) 0 else 1
            b.nav.selectedItemId = if (tab == 0) R.id.nav_browser else R.id.nav_list
        }

        b.nav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_browser -> showTab(0)
                R.id.nav_list -> showTab(1)
                R.id.nav_log -> {
                    startActivity(android.content.Intent(this, LogActivity::class.java))
                    return@setOnItemSelectedListener false
                }
            }
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    browser.exitFullscreenIfNeeded() -> Unit
                    tab == 1 -> b.nav.selectedItemId = R.id.nav_browser
                    browser.goBackIfPossible() -> Unit
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Repo.items.collect { all ->
                        val badge = b.nav.getOrCreateBadge(R.id.nav_list)
                        badge.number = all.size
                        badge.isVisible = all.isNotEmpty()
                    }
                }
                launch {
                    Repo.detected.collect { item ->
                        if (tab == 0) {
                            val res = if (item.resolution.isNotEmpty()) " (${item.resolution})" else ""
                            Snackbar.make(b.root, "m3u8 감지됨: ${item.title}$res", Snackbar.LENGTH_LONG)
                                .setAnchorView(b.nav)
                                .setAction("목록 보기") { showList() }
                                .show()
                        }
                    }
                }
            }
        }
    }

    fun showList() {
        b.nav.selectedItemId = R.id.nav_list
    }

    private fun showTab(i: Int) {
        if (tab != i) Dbg.i("UI", if (i == 0) "브라우저 탭" else "목록 탭")
        tab = i
        supportFragmentManager.beginTransaction().apply {
            if (i == 0) {
                show(browser); hide(listFrag)
            } else {
                show(listFrag); hide(browser)
            }
        }.commit()
    }

    /** 웹 영상 전체화면 진입/해제 */
    fun setVideoFullscreen(on: Boolean) {
        b.nav.visibility = if (on) View.GONE else View.VISIBLE
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
