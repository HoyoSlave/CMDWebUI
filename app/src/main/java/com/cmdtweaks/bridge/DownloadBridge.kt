package com.cmdtweaks.bridge

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.widget.Toast
import androidx.core.net.toUri
import com.cmdtweaks.R
import com.cmdtweaks.webview.WebUISession

class DownloadBridge(private val session: WebUISession) {
    companion object {
        private const val TAG = "DownloadBridge"
    }

    @JavascriptInterface
    fun download(url: String, contentDisposition: String?, mimeType: String?) {
        val view = session.webView ?: return
        val context = view.context
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(url.toUri()).apply {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(fileName)
                if (!mimeType.isNullOrEmpty()) {
                    setMimeType(mimeType)
                }
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                addRequestHeader("User-Agent", WebSettings.getDefaultUserAgent(context))
            }
            dm.enqueue(request)
            view.post {
                Toast.makeText(context, R.string.start_downloading, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            view.post {
                Toast.makeText(
                    context,
                    context.getString(R.string.download_failed, e.message ?: "Unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun save(base64Payload: String, fileName: String, mimeType: String) {
        try {
            val bytes = Base64.decode(base64Payload, Base64.DEFAULT)
            session.onSaveBlobRequest?.invoke(bytes, fileName, mimeType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode blob download", e)
        }
    }
}
