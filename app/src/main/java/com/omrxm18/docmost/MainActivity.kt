package com.omrxm18.docmost

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val startUrl = BuildConfig.DOCMOST_URL

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val openDocumentLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            deliverFileChooserResult(uri?.let { arrayOf(it) })
        }

    private val openMultipleDocumentsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            deliverFileChooserResult(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        }

    private val downloadManager: DownloadManager by lazy {
        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private fun startDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )

            // Attach the login cookie so downloads of private files work too.
            CookieManager.getInstance().getCookie(url)?.let { cookie ->
                if (cookie.isNotEmpty()) request.addRequestHeader("Cookie", cookie)
            }

            downloadManager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deliverFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    /** WebView reports extensions like ".pdf" as well as MIME types; the document pickers need MIME types. */
    private fun acceptTypes(params: FileChooserParams): Array<String> {
        val types = params.acceptTypes
            .map { type ->
                if (type.contains("/")) {
                    type
                } else {
                    MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(type.removePrefix(".").lowercase())
                }
            }
            .filterNotNull()
            .distinct()
            .toTypedArray()
        return types.ifEmpty { arrayOf("*/*") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE) // color shown behind the status/nav bars
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(container)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)

        val host = startUrl.toUri().host
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Keep Docmost inside the app, send other sites to the browser
                if (request.url.host == host) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                return true
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                // A second chooser request would leave the first callback hanging.
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val types = acceptTypes(fileChooserParams)
                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    openMultipleDocumentsLauncher.launch(types)
                } else {
                    openDocumentLauncher.launch(types)
                }
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        // Write cookies to disk so the login survives the app being killed
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
