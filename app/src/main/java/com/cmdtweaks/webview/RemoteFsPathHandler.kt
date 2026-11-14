package com.cmdtweaks.webview

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.annotation.WorkerThread
import androidx.webkit.WebViewAssetLoader
import com.cmdtweaks.ui.MonetCssEngine
import com.cmdtweaks.ui.WindowInsetsState
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

class RemoteFsPathHandler(
    private val context: Context,
    directory: File,
    fs: FileSystemManager,
    private val insetsProvider: () -> WindowInsetsState,
    private val onInsetsRequested: (Boolean) -> Unit
) : WebViewAssetLoader.PathHandler {

    companion object {
        private const val TAG = "RemoteFsPathHandler"
        private val FORBIDDEN_PREFIXES = arrayOf("/data/data", "/data/system")
    }

    private val rootDir: ExtendedFile

    init {
        try {
            val remoteTarget = fs.getFile(directory.absolutePath)
            val canonicalPath = normalizeDirectoryPath(remoteTarget)
            if (FORBIDDEN_PREFIXES.any { canonicalPath.startsWith(it) }) {
                throw IllegalArgumentException("Forbidden directory path: $directory")
            }
            rootDir = fs.getFile(canonicalPath)
        } catch (e: IOException) {
            throw IllegalArgumentException("Failed to resolve canonical path for: ${directory.path}", e)
        }
    }

    @WorkerThread
    override fun handle(path: String): WebResourceResponse {
        if (path == "internal/insets.css") {
            onInsetsRequested(true)
            val cssBytes = insetsProvider().cssVariables.toByteArray(StandardCharsets.UTF_8)
            return WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(cssBytes))
        }

        if (path == "internal/colors.css") {
            val monetEnabled = context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("enable_monet", true)
            val cssText = if (monetEnabled) MonetCssEngine.currentCss else ""
            return WebResourceResponse(
                "text/css",
                "utf-8",
                ByteArrayInputStream(cssText.toByteArray(StandardCharsets.UTF_8))
            )
        }

        try {
            val child = resolveSafeChild(rootDir, path)
            if (child != null) {
                val rawStream = child.newInputStream()
                val stream: InputStream = if (path.endsWith(".svgz", ignoreCase = true)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                val mimeType = MimeResolver.resolve(path)
                return WebResourceResponse(mimeType, null, stream)
            } else {
                Log.e(TAG, "Path outside mounted webroot: $path")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open web asset: $path", e)
        }

        return WebResourceResponse(null, null, null)
    }

    private fun normalizeDirectoryPath(dir: ExtendedFile): String {
        val canonical = dir.canonicalPath
        return if (canonical.endsWith("/")) canonical else "$canonical/"
    }

    private fun resolveSafeChild(parent: ExtendedFile, relativePath: String): ExtendedFile? {
        val baseCanonical = normalizeDirectoryPath(parent)
        val candidate = parent.getChildFile(relativePath)
        val candidateCanonical = candidate.canonicalPath
        return if (candidateCanonical.startsWith(baseCanonical)) candidate else null
    }
}
