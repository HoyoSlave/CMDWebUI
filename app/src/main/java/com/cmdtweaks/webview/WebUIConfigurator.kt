package com.cmdtweaks.webview

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.cmdtweaks.WebUIActivity
import com.cmdtweaks.bridge.DownloadBridge
import com.cmdtweaks.bridge.KsuJsBridge
import com.cmdtweaks.core.PackageRepository
import com.cmdtweaks.core.PrivilegeManager
import com.cmdtweaks.ui.WindowInsetsState
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

object WebUIConfigurator {
    private const val WEB_DOMAIN = "mui.kernelsu.org"
    private const val ENTRY_URL = "https://mui.kernelsu.org/index.html"
    private const val SCHEME_KSU = "ksu"
    private const val HOST_ICON = "icon"

    private const val BLOB_DOWNLOAD_HOOK_JS = """
        (function() {
            if (window.ksu_download_enabled) return;
            window.ksu_download_enabled = true;
            const blobMap = new Map();
            const orgCreate = URL.createObjectURL;
            URL.createObjectURL = (obj) => {
                const url = orgCreate(obj);
                if (obj instanceof Blob) blobMap.set(url, obj);
                return url;
            };
            const orgRevoke = URL.revokeObjectURL;
            URL.revokeObjectURL = (url) => {
                setTimeout(() => blobMap.delete(url), 10000);
                orgRevoke(url);
            };
            const handleDownload = async (a) => {
                const urlParsed = new URL(a.href, location.href);
                const url = urlParsed.href;
                const fileName = a.download || url.split("/").pop().split("?")[0] || "download.bin";
                const isInternal = urlParsed.hostname === 'mui.kernelsu.org';
                if (url.startsWith('blob:') || url.startsWith('data:') || isInternal) {
                    const blob = (url.startsWith('blob:') && blobMap.has(url)) ? blobMap.get(url) : await (await fetch(url)).blob();
                    if (blob.size > 16 * 1024 * 1024) {
                        console.error("File too large, please use FileOutputStreamInterface instead.");
                        return;
                    }
                    const reader = new FileReader();
                    reader.onload = () => {
                        ksu_download.save(reader.result.split(',')[1], fileName, blob.type);
                    };
                    reader.readAsDataURL(blob);
                } else {
                    ksu_download.download(url, null, null);
                }
            };
            document.addEventListener("click", (e) => {
                const a = e.target.closest("a[download]");
                if (a) {
                    e.preventDefault();
                    handleDownload(a);
                }
            }, true);
            const orgClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                this.hasAttribute("download") ? handleDownload(this) : orgClick.apply(this, arguments);
            };
        })();
    """

    @SuppressLint("SetJavaScriptEnabled")
    fun createContainerView(activity: WebUIActivity, session: WebUISession): FrameLayout {
        val container = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val webView = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val density = resources.displayMetrics.density

            ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
                val raw = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime()
                )
                val updated = WindowInsetsState(
                    top = (raw.top / density).toInt(),
                    bottom = (raw.bottom / density).toInt(),
                    left = (raw.left / density).toInt(),
                    right = (raw.right / density).toInt()
                )

                if (session.insets != updated) {
                    session.insets = updated
                    if (session.isEdgeToEdgeEnabled) {
                        evaluateJavascript(session.insets.jsUpdateScript, null)
                    }
                }

                if (session.isEdgeToEdgeEnabled) {
                    view.setPadding(0, 0, 0, 0)
                } else {
                    view.setPadding(raw.left, raw.top, raw.right, raw.bottom)
                }
                WindowInsetsCompat.CONSUMED
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                textZoom = (resources.configuration.fontScale * 100).toInt()
            }
        }

        session.webView = webView
        container.addView(webView)
        return container
    }

    fun attachFileSystemAndLoad(
        activity: WebUIActivity,
        fs: FileSystemManager,
        session: WebUISession
    ) {
        session.fileSystem = fs
        val (resolvedModDir, resolvedWebRoot) = PrivilegeManager.resolveWebRoot(fs)
        session.moduleDir = resolvedModDir

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(WEB_DOMAIN)
            .addPathHandler(
                "/",
                RemoteFsPathHandler(
                    context = activity,
                    directory = File(resolvedWebRoot),
                    fs = fs,
                    insetsProvider = { session.insets },
                    onInsetsRequested = { enable -> activity.setEdgeToEdgeMode(enable) }
                )
            )
            .build()

        val client = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.scheme.equals(SCHEME_KSU, ignoreCase = true) &&
                    url.host.equals(HOST_ICON, ignoreCase = true)
                ) {
                    val pkgName = url.path?.removePrefix("/")
                    if (!pkgName.isNullOrEmpty()) {
                        val iconBmp = PackageRepository.loadIconBitmap(activity, pkgName, 144)
                        if (iconBmp != null) {
                            val bos = ByteArrayOutputStream()
                            iconBmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                            return WebResourceResponse(
                                "image/png",
                                null,
                                ByteArrayInputStream(bos.toByteArray())
                            )
                        }
                    }
                }
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(BLOB_DOWNLOAD_HOOK_JS, null)
            }
        }

        session.webView?.apply {
            val ksuBridge = KsuJsBridge(session)
            session.jsBridge = ksuBridge
            addJavascriptInterface(ksuBridge, "ksu")

            val downloadBridge = DownloadBridge(session)
            addJavascriptInterface(downloadBridge, "ksu_download")

            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                downloadBridge.download(url, contentDisposition, mimeType)
            }

            webViewClient = client
            webChromeClient = buildChromeClient(activity, session)
            loadUrl(ENTRY_URL)
        }
    }

    private fun buildChromeClient(
        activity: WebUIActivity,
        session: WebUISession
    ): WebChromeClient {
        return object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                session.fileChooserCallback?.onReceiveValue(null)
                session.fileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                    intent.type?.startsWith(".") == true
                ) {
                    intent.type = "*/*"
                }

                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                return try {
                    activity.fileChooserLauncher.launch(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    session.fileChooserCallback?.onReceiveValue(null)
                    session.fileChooserCallback = null
                    false
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(activity)
                    .setTitle("Alert")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(activity)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(activity).apply {
                    if (defaultValue != null) setText(defaultValue)
                }
                val container = FrameLayout(activity)
                val marginPx = (20 * activity.resources.displayMetrics.density).toInt()
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = marginPx
                    rightMargin = marginPx
                }
                container.addView(input, params)

                AlertDialog.Builder(activity)
                    .setTitle("Prompt")
                    .setMessage(message)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
    }
}
