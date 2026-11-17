package com.omrxm18.docmost

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("docmost_prefs", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    companion object {
        private const val KEY_SERVER_URL = "server_url"
    }
}
