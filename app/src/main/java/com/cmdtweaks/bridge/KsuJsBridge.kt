package com.cmdtweaks.bridge

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cmdtweaks.App
import com.cmdtweaks.WebUIActivity
import com.cmdtweaks.core.PackageRepository
import com.cmdtweaks.core.PrivilegeManager
import com.cmdtweaks.core.ShellEngine
import com.cmdtweaks.webview.WebUISession
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.internal.UiThreadHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

class KsuJsBridge(private val session: WebUISession) {

    private val webView get() = session.webView!!
    private val context get() = webView.context
    private val moduleDir get() = session.moduleDir

    val fileStreamBridge = FileStreamBridge(session)

    @JavascriptInterface
    fun exec(cmd: String): String {
        return ShellEngine.executeFast(cmd, moduleDir)
    }

    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String) {
        exec(cmd, null, callbackFunc)
    }

    @JavascriptInterface
    fun exec(cmd: String, optionsJson: String?, callbackFunc: String) {
        val fullCommand = buildCommandWithOptions(cmd, null, optionsJson)
        App.executor.submit {
            val result = ShellEngine.executeSync(fullCommand, moduleDir)
            val jsPayload = buildString {
                append("(function() { try { ")
                append(callbackFunc)
                append("(")
                append(result.code)
                append(", ")
                append(JSONObject.quote(result.stdoutText))
                append(", ")
                append(JSONObject.quote(result.stderrText))
                append("); } catch(e) { console.error(e); } })();")
            }
            webView.post { webView.evaluateJavascript(jsPayload, null) }
        }
    }

    @JavascriptInterface
    fun spawn(command: String, argsJson: String?, optionsJson: String?, callbackFunc: String) {
        val fullCommand = ShellEngine.rewritePathForBackend(
            buildCommandWithOptions(command, argsJson, optionsJson),
            moduleDir
        )

        fun emitStream(streamName: String, line: String) {
            val js = "(function() { try { ${callbackFunc}.${streamName}.emit('data', ${
                JSONObject.quote(line)
            }); } catch(e) { console.error('emitData', e); } })();"
            webView.post { webView.evaluateJavascript(js, null) }
        }

        if (!PrivilegeManager.isShizukuActive) {
            val shell = ShellEngine.newDedicatedRootShell()
            val outList = object : CallbackList<String>(UiThreadHandler::runAndWait) {
                override fun onAddElement(s: String) = emitStream("stdout", s)
            }
            val errList = object : CallbackList<String>(UiThreadHandler::runAndWait) {
                override fun onAddElement(s: String) = emitStream("stderr", s)
            }

            val future = shell.newJob().add(fullCommand).to(outList, errList).enqueue()
            CompletableFuture.supplyAsync { future.get() }
                .thenAccept { res ->
                    emitProcessCompletion(callbackFunc, res.code, res.err.joinToString("\n"))
                }
                .whenComplete { _, _ -> runCatching { shell.close() } }
        } else {
            App.executor.submit {
                try {
                    val proc = ShellEngine.createShizukuProcess(fullCommand)
                    val errCollector = ArrayList<String>()
                    val outThread = Thread {
                        proc.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { emitStream("stdout", it) }
                        }
                    }
                    val errThread = Thread {
                        proc.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach {
                                errCollector.add(it)
                                emitStream("stderr", it)
                            }
                        }
                    }
                    outThread.start()
                    errThread.start()
                    val code = proc.waitFor()
                    outThread.join()
                    errThread.join()
                    emitProcessCompletion(callbackFunc, code, errCollector.joinToString("\n"))
                } catch (e: Throwable) {
                    emitProcessCompletion(callbackFunc, 1, e.message ?: "Spawn failed")
                }
            }
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        webView.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        val act = context as? Activity
        if (act != null) {
            Handler(Looper.getMainLooper()).post {
                setSystemBarsVisible(act.window, !enable)
            }
        }
        enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    fun enableEdgeToEdge(enable: Boolean = true) {
        (context as? WebUIActivity)?.setEdgeToEdgeMode(enable)
    }

    @JavascriptInterface
    fun enableInsets(enable: Boolean = true) = enableEdgeToEdge(enable)

    @JavascriptInterface
    fun moduleInfo(): String {
        return JSONObject().apply {
            put("moduleDir", moduleDir)
            put("id", File(moduleDir).name)
            put("mode", PrivilegeManager.activeMode.label)
        }.toString()
    }

    @JavascriptInterface
    fun listPackages(type: String): String {
        val filterMode = type.lowercase()
        val names = PackageRepository.getPackagesMap(context).values
            .filter { pkg ->
                val flags = pkg.applicationInfo?.flags ?: 0
                when (filterMode) {
                    "system" -> (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    "user" -> (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    else -> true
                }
            }
            .map { it.packageName }
            .sorted()

        return JSONArray().apply {
            names.forEach { put(it) }
        }.toString()
    }

    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String {
        val requested = JSONArray(packageNamesJson)
        val output = JSONArray()
        for (i in 0 until requested.length()) {
            val pkgName = requested.getString(i)
            val pkg = PackageRepository.findPackageInfo(context, pkgName)
            if (pkg != null) {
                val app = pkg.applicationInfo
                val label = PackageRepository.resolveLabel(context, pkg)
                output.put(
                    JSONObject().apply {
                        put("packageName", pkg.packageName)
                        put("versionName", pkg.versionName ?: "")
                        put("versionCode", PackageInfoCompat.getLongVersionCode(pkg))
                        put("appLabel", label)
                        put("name", label)
                        put("label", label)
                        put("isSystem", app != null && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        put("uid", app?.uid ?: 0)
                    }
                )
            } else {
                output.put(
                    JSONObject().apply {
                        put("packageName", pkgName)
                        put("appLabel", pkgName)
                        put("name", pkgName)
                        put("label", pkgName)
                        put("error", "Package not found")
                    }
                )
            }
        }
        return output.toString()
    }

    @JavascriptInterface
    fun exit() {
        (context as? Activity)?.finish()
    }

    @JavascriptInterface
    fun fileOutputStream(): FileStreamBridge = fileStreamBridge

    fun release() {
        fileStreamBridge.closeAll()
    }

    private fun buildCommandWithOptions(
        command: String,
        argsJson: String?,
        optionsJson: String?
    ): String {
        val sb = StringBuilder()
        if (!optionsJson.isNullOrEmpty()) {
            runCatching {
                val opts = JSONObject(optionsJson)
                val cwd = opts.optString("cwd")
                if (cwd.isNotEmpty()) {
                    sb.append("cd ").append(cwd).append(";")
                }
                opts.optJSONObject("env")?.let { env ->
                    val keys = env.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        sb.append("export ").append(k).append("=").append(env.getString(k)).append(";")
                    }
                }
            }
        }

        sb.append(command)
        if (!argsJson.isNullOrEmpty()) {
            runCatching {
                val arr = JSONArray(argsJson)
                for (i in 0 until arr.length()) {
                    sb.append(" ").append(arr.getString(i))
                }
            }
        }
        return sb.toString()
    }

    private fun emitProcessCompletion(callbackFunc: String, exitCode: Int, errorText: String) {
        val exitJs = "(function() { try { ${callbackFunc}.emit('exit', $exitCode); } catch(e) { console.error(e); } })();"
        webView.post { webView.evaluateJavascript(exitJs, null) }

        if (exitCode != 0) {
            val errJs = "(function() { try { var err = new Error(); err.exitCode = $exitCode; err.message = ${
                JSONObject.quote(errorText)
            }; ${callbackFunc}.emit('error', err); } catch(e) { console.error(e); } })();"
            webView.post { webView.evaluateJavascript(errJs, null) }
        }
    }

    private fun setSystemBarsVisible(window: Window, visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
