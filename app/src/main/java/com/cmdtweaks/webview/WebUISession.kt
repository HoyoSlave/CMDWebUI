package com.cmdtweaks.webview

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView
import com.cmdtweaks.bridge.KsuJsBridge
import com.cmdtweaks.core.PrivilegeManager
import com.cmdtweaks.ui.WindowInsetsState
import com.topjohnwu.superuser.nio.FileSystemManager

class WebUISession {
    var webView: WebView? = null
    var jsBridge: KsuJsBridge? = null
    var fileSystem: FileSystemManager? = null
    var moduleName: String = "CMD-Tweaks"
    var moduleDir: String = PrivilegeManager.PRIMARY_MODULE_DIR
    var insets: WindowInsetsState = WindowInsetsState(0, 0, 0, 0)
    var isEdgeToEdgeEnabled: Boolean = false
    var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    var onSaveBlobRequest: ((ByteArray, String, String) -> Unit)? = null
}
