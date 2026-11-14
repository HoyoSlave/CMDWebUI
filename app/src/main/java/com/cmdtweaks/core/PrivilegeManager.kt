package com.cmdtweaks.core

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.annotation.MainThread
import com.cmdtweaks.App
import com.cmdtweaks.BuildConfig
import com.cmdtweaks.services.PrivilegeFsService
import com.cmdtweaks.services.ShizukuFsService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

object PrivilegeManager {
    const val PRIMARY_MODULE_DIR = "/data/adb/modules/cmd_tweaks"
    const val FALLBACK_AXERON_DIR = "/data/user_de/0/com.android.shell/axeron/plugins/cmd_tweaks"
    private const val SHIZUKU_REQ_CODE = 2001

    enum class BackendMode(val label: String) {
        NONE("none"),
        ROOT("root"),
        SHIZUKU("shizuku")
    }

    interface Listener {
        fun onBackendReady(fs: FileSystemManager, mode: BackendMode)
        fun onBackendFailed()
    }

    @Volatile
    var activeMode: BackendMode = BackendMode.NONE
        private set

    @Volatile
    var fileSystem: FileSystemManager? = null
        private set

    private var isConnecting = false
    private val listeners = CopyOnWriteArraySet<Listener>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                notifyFailure()
                return
            }
            val fs = runCatching { FileSystemManager.getRemote(binder) }.getOrNull()
            if (fs == null) {
                notifyFailure()
                return
            }
            fileSystem = fs
            isConnecting = false
            for (listener in listeners) {
                listener.onBackendReady(fs, activeMode)
                listeners.remove(listener)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileSystem = null
            activeMode = BackendMode.NONE
            isConnecting = false
        }
    }

    private val shizukuPermListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != SHIZUKU_REQ_CODE) return
            Shizuku.removeRequestPermissionResultListener(this)
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                activeMode = BackendMode.SHIZUKU
                bindShizukuFsService()
            } else {
                notifyFailure()
            }
        }
    }

    val isShizukuActive: Boolean
        get() = when (activeMode) {
            BackendMode.SHIZUKU -> true
            BackendMode.ROOT -> false
            BackendMode.NONE -> false
        }

    @MainThread
    fun connect(listener: Listener) {
        val existingFs = fileSystem
        if (existingFs != null && activeMode != BackendMode.NONE) {
            listener.onBackendReady(existingFs, activeMode)
            return
        }
        listeners.add(listener)
        if (!isConnecting) {
            isConnecting = true
            probeBackendAsync()
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun resolveWebRoot(fs: FileSystemManager): Pair<String, String> {
        val candidates = if (isShizukuActive) {
            listOf(
                FALLBACK_AXERON_DIR to "$FALLBACK_AXERON_DIR/webroot",
                FALLBACK_AXERON_DIR to FALLBACK_AXERON_DIR,
                PRIMARY_MODULE_DIR to "$PRIMARY_MODULE_DIR/webroot",
                PRIMARY_MODULE_DIR to PRIMARY_MODULE_DIR
            )
        } else {
            listOf(
                PRIMARY_MODULE_DIR to "$PRIMARY_MODULE_DIR/webroot",
                PRIMARY_MODULE_DIR to PRIMARY_MODULE_DIR,
                FALLBACK_AXERON_DIR to "$FALLBACK_AXERON_DIR/webroot",
                FALLBACK_AXERON_DIR to FALLBACK_AXERON_DIR
            )
        }

        for ((modDir, webRoot) in candidates) {
            val hasIndex = runCatching { fs.getFile("$webRoot/index.html").exists() }.getOrDefault(false)
            if (hasIndex) return modDir to webRoot
        }

        for ((modDir, webRoot) in candidates) {
            val hasDir = runCatching {
                val dir = fs.getFile(webRoot)
                dir.exists() && dir.isDirectory
            }.getOrDefault(false)
            if (hasDir) return modDir to webRoot
        }

        return if (isShizukuActive) {
            FALLBACK_AXERON_DIR to "$FALLBACK_AXERON_DIR/webroot"
        } else {
            PRIMARY_MODULE_DIR to "$PRIMARY_MODULE_DIR/webroot"
        }
    }

    private fun checkRootAvailable(): Boolean {
        return runCatching {
            val shared = Shell.getShell()
            if (shared.isRoot) return true
            // Jika shared shell sebelumnya terbuat sebagai non-root sebelum izin diberikan, buat shell baru untuk cek ulang
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(5)
                .build()
                .use { it.isRoot }
        }.getOrDefault(false)
    }

    private fun probeBackendAsync() {
        App.executor.submit {
            // 1. Selalu cek Root via libsu secara langsung (tanpa File("/system/bin/su").exists() yang diblokir SELinux di KernelSU/APatch!)
            if (checkRootAvailable()) {
                App.mainHandler.post {
                    activeMode = BackendMode.ROOT
                    RootService.bind(Intent(App.instance, PrivilegeFsService::class.java), serviceConnection)
                }
                return@submit
            }

            // 2. Jika Root tidak tersedia/ditolak, cek Shizuku
            val shizukuGranted = runCatching {
                Shizuku.pingBinder() &&
                    !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

            if (shizukuGranted) {
                App.mainHandler.post {
                    activeMode = BackendMode.SHIZUKU
                    bindShizukuFsService()
                }
                return@submit
            }

            var shizukuAlive = false
            for (i in 0 until 10) {
                if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    shizukuAlive = true
                    break
                }
                runCatching { Thread.sleep(50) }
            }

            App.mainHandler.post {
                if (shizukuAlive) {
                    requestOrBindShizuku()
                } else {
                    notifyFailure()
                }
            }
        }
    }

    private fun requestOrBindShizuku() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                notifyFailure()
                return
            }
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    activeMode = BackendMode.SHIZUKU
                    bindShizukuFsService()
                }
                Shizuku.shouldShowRequestPermissionRationale() -> notifyFailure()
                else -> {
                    Shizuku.addRequestPermissionResultListener(shizukuPermListener)
                    Shizuku.requestPermission(SHIZUKU_REQ_CODE)
                }
            }
        } catch (_: Throwable) {
            notifyFailure()
        }
    }

    private fun bindShizukuFsService() {
        ShellEngine.prewarmShizukuPool()
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(App.instance.packageName, ShizukuFsService::class.java.name)
            )
                .daemon(true)
                .processNameSuffix("fs")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (_: Throwable) {
            notifyFailure()
        }
    }

    private fun notifyFailure() {
        isConnecting = false
        activeMode = BackendMode.NONE
        for (listener in listeners) {
            listener.onBackendFailed()
            listeners.remove(listener)
        }
    }
}
