package com.omrxm18.docmost

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Only claim the pull gesture when the WebView is already scrolled to the top. */
class WebViewSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {
    var webView: WebView? = null

    override fun canChildScrollUp(): Boolean = (webView?.scrollY ?: 0) > 0
}
