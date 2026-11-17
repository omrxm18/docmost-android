package com.omrxm18.docmost

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.util.UUID

class MainActivity : FragmentActivity() {
    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var lockView: View
    private lateinit var swipeRefresh: WebViewSwipeRefreshLayout
    private val startUrl = BuildConfig.DOCMOST_URL
    private var locked = true

    companion object {
        private const val KEY_ERROR_VISIBLE = "error_visible"
        private const val KEY_LOCKED = "locked"
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var captureUri: Uri? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = captureUri
            captureUri = null
            deliverFileChooserResult(if (success && uri != null) arrayOf(uri) else null)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            pendingPermissionRequest = null
            val granted = mutableListOf<String>()
            if (results[Manifest.permission.CAMERA] == true) {
                granted += PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (results[Manifest.permission.RECORD_AUDIO] == true) {
                granted += PermissionRequest.RESOURCE_AUDIO_CAPTURE
            }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }

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

    private fun launchCameraCapture() {
        val file = File(cacheDir, "camera/${UUID.randomUUID()}.jpg")
        file.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        captureUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun requestWebPermissions(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            needed += Manifest.permission.CAMERA
        }
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (needed.isEmpty()) {
            request.deny()
            return
        }
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = request
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun showError(show: Boolean) {
        errorView.isVisible = show
        webView.isVisible = !show
        swipeRefresh.isEnabled = !show
        if (show) swipeRefresh.isRefreshing = false
    }

    private fun lock() {
        locked = true
        lockView.isVisible = true
    }

    private fun unlock() {
        locked = false
        lockView.isVisible = false
    }

    private fun promptBiometric() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometrics or device credential set up: locking would make the app unusable.
            unlock()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun handleShareIntent() {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return

        val preview = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(24, 0, 24, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.shared_content_title)
            .setView(ScrollView(this).apply { addView(preview) })
            .setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        installSplashScreen()
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        swipeRefresh = WebViewSwipeRefreshLayout(this).apply {
            webView = this@MainActivity.webView
            addView(
                this@MainActivity.webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnRefreshListener { this@MainActivity.webView.reload() }
        }
        errorView = layoutInflater.inflate(R.layout.error_view, null, false)
        errorView.findViewById<Button>(R.id.retry_button).setOnClickListener {
            showError(false)
            webView.reload()
        }
        lockView = layoutInflater.inflate(R.layout.lock_view, null, false)
        lockView.findViewById<Button>(R.id.unlock_button).setOnClickListener {
            promptBiometric()
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE) // color shown behind the status/nav bars
            addView(
                swipeRefresh,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                errorView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                lockView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(container)
        showError(savedInstanceState?.getBoolean(KEY_ERROR_VISIBLE, false) ?: false)
        locked = savedInstanceState?.getBoolean(KEY_LOCKED, true) ?: true
        lockView.isVisible = locked

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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                showError(false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showError(true)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) showError(true)
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

                val imageCapture = fileChooserParams.isCaptureEnabled &&
                        (fileChooserParams.acceptTypes.isEmpty() ||
                                fileChooserParams.acceptTypes.any {
                                    it == "*/*" || it.startsWith("image/")
                                })
                when {
                    imageCapture -> launchCameraCapture()
                    fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE ->
                        openMultipleDocumentsLauncher.launch(acceptTypes(fileChooserParams))
                    else -> openDocumentLauncher.launch(acceptTypes(fileChooserParams))
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                requestWebPermissions(request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermissionRequest == request) pendingPermissionRequest = null
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
            handleShareIntent()
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent()
    }

    override fun onResume() {
        super.onResume()
        if (locked) promptBiometric()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ERROR_VISIBLE, errorView.isVisible)
        outState.putBoolean(KEY_LOCKED, locked)
        webView.saveState(outState)
    }

    override fun onPause() {
        // Write cookies to disk so the login survives the app being killed
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Rotation recreates the activity; only backgrounding it should re-lock.
        if (!isChangingConfigurations) lock()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
