package com.termux.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.termux.R

/**
 * 全屏展示 loadHtml 生成的网页（简化版）：横屏 + 普通 WebView，
 * 设置桌面 UA 和 viewport，保证不白屏、布局尽量靠近电脑端。
 * 通过 WebViewClient 在应用内处理链接跳转、重定向，避免「点击/跳转没反应」。
 */
class FullScreenWebActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_web)

        // 使用 Intent extra 传递 HTML，避免依赖静态字段在进程被系统回收后变为 null 导致空白/无反应
        val html = intent.getStringExtra(EXTRA_HTML)

        if (html.isNullOrBlank()) {
            finish()
            return
        }

        val webView = findViewById<WebView>(R.id.web_view)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            // 桌面 UA，尽量走电脑端布局
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        // 在 WebView 内处理链接、重定向、JS 跳转，否则会「完全没反应」
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString() ?: return false
                return handleExternalUrl(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                return handleExternalUrl(view, url)
            }
        }
        // target="_blank" / window.open：在同一 WebView 中加载，避免新窗口无反应
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.setWebView(view ?: webView)
                resultMsg.sendToTarget()
                return true
            }
        }
        val htmlWithViewport = ensureDesktopViewport(html)
        webView.loadDataWithBaseURL("http://localhost/", htmlWithViewport, "text/html", "utf-8", null)
    }

    /** 仅对 tel/mailto/外部应用协议用系统处理，其余一律在 WebView 内加载 */
    private fun handleExternalUrl(view: WebView, url: String): Boolean {
        return when {
            url.startsWith("tel:") -> {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                true
            }
            url.startsWith("mailto:") -> {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                true
            }
            url.startsWith("intent:") -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                } catch (_: Exception) { }
                true
            }
            else -> false
        }
    }

    /** 只保证有桌面宽度的 viewport，不再强制 overflow 以避免白屏和怪异布局 */
    private fun ensureDesktopViewport(html: String): String {
        val viewportMeta = """<meta name="viewport" content="width=1280">"""
        return when {
            html.contains("viewport", ignoreCase = true) ->
                html.replace(
                    Regex("""<meta[^>]*name\s*=\s*["']viewport["'][^>]*/?>""", RegexOption.IGNORE_CASE),
                    viewportMeta
                )
            html.contains("<head", ignoreCase = true) ->
                html.replace(Regex("""<head\s*>""", RegexOption.IGNORE_CASE), "<head>$viewportMeta")
            else -> "<!DOCTYPE html><html><head>$viewportMeta</head><body>$html</body></html>"
        }
    }

    companion object {
        const val EXTRA_HTML = "html"
    }
}
