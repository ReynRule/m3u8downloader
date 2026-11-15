package com.m3u8.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.m3u8.downloader.databinding.FragmentBrowserBinding

class BrowserFragment : Fragment() {

    private var _b: FragmentBrowserBinding? = null
    private val b get() = _b!!

    @Volatile private var curUrl = ""
    @Volatile private var curTitle = ""
    @Volatile private var ua = ""

    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentBrowserBinding.inflate(inflater, container, false)
        return b.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val wv = b.web
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            // 일반 Chrome 처럼 보이게 (WebView 표식 제거)
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        }
        ua = wv.settings.userAgentString
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    val ref = request.requestHeaders?.get("Referer") ?: curUrl
                    Detector.onUrl(request.url.toString(), curUrl, ref, curTitle, ua)
                }
                return null // 정상적으로 페이지가 계속 로드되도록 그대로 통과
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                if (url != null) Detector.onUrl(url, curUrl, curUrl, curTitle, ua)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme ?: return false
                return scheme !in setOf("http", "https", "about", "data", "blob", "javascript")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                curUrl = url ?: ""
                _b?.let {
                    it.progress.visibility = View.VISIBLE
                    if (!it.url.hasFocus()) it.url.setText(url)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                _b?.progress?.visibility = View.INVISIBLE
                CookieManager.getInstance().flush()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (url != null) {
                    curUrl = url
                    _b?.let { if (!it.url.hasFocus()) it.url.setText(url) }
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _b?.progress?.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                curTitle = title ?: ""
            }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null || callback == null) return
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCb = callback
                b.fullscreen.addView(view)
                b.fullscreen.visibility = View.VISIBLE
                (activity as? MainActivity)?.setImmersive(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        b.btnBack.setOnClickListener { goBackIfPossible() }
        b.btnForward.setOnClickListener { if (wv.canGoForward()) wv.goForward() }
        b.btnReload.setOnClickListener { wv.reload() }
        b.url.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                go(b.url.text.toString())
                true
            } else false
        }

        if (savedInstanceState == null) wv.loadUrl(HOME)
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        b.web.loadUrl(u)
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.url.windowToken, 0)
        b.url.clearFocus()
        b.web.requestFocus()
    }

    fun goBackIfPossible(): Boolean {
        val bb = _b ?: return false
        return if (bb.web.canGoBack()) {
            bb.web.goBack()
            true
        } else false
    }

    fun exitFullscreenIfNeeded(): Boolean {
        if (customView == null) return false
        exitFullscreen()
        return true
    }

    private fun exitFullscreen() {
        val bb = _b ?: return
        bb.fullscreen.removeAllViews()
        bb.fullscreen.visibility = View.GONE
        customCb?.onCustomViewHidden()
        customView = null
        customCb = null
        (activity as? MainActivity)?.setImmersive(false)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) _b?.web?.onPause() else _b?.web?.onResume()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) _b?.web?.onResume()
    }

    override fun onPause() {
        _b?.web?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroyView() {
        _b?.web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        _b = null
        super.onDestroyView()
    }

    companion object {
        private const val HOME = "https://www.google.com"
    }
}
