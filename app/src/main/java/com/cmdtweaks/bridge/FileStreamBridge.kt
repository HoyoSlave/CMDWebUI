package com.cmdtweaks.bridge

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.cmdtweaks.core.ShellEngine
import com.cmdtweaks.webview.WebUISession
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileOutputStream
import java.io.BufferedOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileStreamBridge(private val session: WebUISession) {
    companion object {
        private const val TAG = "FileStreamBridge"
        private const val BUFFER_BYTES = 64 * 1024
    }

    private val activeStreams = ConcurrentHashMap<String, BufferedOutputStream>()

    @JavascriptInterface
    fun open(path: String): String = open(path, false)

    @JavascriptInterface
    fun open(path: String, append: Boolean): String {
        return try {
            val targetPath = ShellEngine.rewritePathForBackend(path, session.moduleDir)
            val fs = session.fileSystem
            val rawStream = if (fs != null) {
                fs.getFile(targetPath).newOutputStream(append)
            } else {
                SuFileOutputStream.open(SuFile(targetPath), append)
            }
            val streamId = UUID.randomUUID().toString()
            activeStreams[streamId] = BufferedOutputStream(rawStream, BUFFER_BYTES)
            streamId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open stream for: $path", e)
            ""
        }
    }

    @JavascriptInterface
    fun writeByte(id: String, byteValue: Int): Boolean {
        val bos = activeStreams[id] ?: return false
        return runCatching {
            synchronized(bos) { bos.write(byteValue) }
            true
        }.getOrElse {
            Log.e(TAG, "writeByte failed", it)
            false
        }
    }

    @JavascriptInterface
    fun write(id: String, base64Chunk: String): Boolean {
        val bos = activeStreams[id] ?: return false
        return runCatching {
            val decoded = Base64.decode(base64Chunk, Base64.NO_WRAP)
            synchronized(bos) { bos.write(decoded) }
            true
        }.getOrElse {
            Log.e(TAG, "write failed", it)
            false
        }
    }

    @JavascriptInterface
    fun flush(id: String): Boolean {
        val bos = activeStreams[id] ?: return false
        return runCatching {
            synchronized(bos) { bos.flush() }
            true
        }.getOrElse {
            Log.e(TAG, "flush failed", it)
            false
        }
    }

    @JavascriptInterface
    fun close(id: String): Boolean {
        val bos = activeStreams.remove(id) ?: return false
        return runCatching {
            synchronized(bos) { bos.close() }
            true
        }.getOrElse {
            Log.e(TAG, "close failed", it)
            false
        }
    }

    fun closeAll() {
        for ((id, bos) in activeStreams) {
            runCatching { synchronized(bos) { bos.close() } }
                .onFailure { Log.e(TAG, "closeAll failed for $id", it) }
        }
        activeStreams.clear()
    }
}
