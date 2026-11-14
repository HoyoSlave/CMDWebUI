package com.cmdtweaks

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.topjohnwu.superuser.Shell
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    companion object {
        lateinit var instance: App
            private set

        val executor: ExecutorService by lazy {
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "CmdTweaks-Worker").apply { isDaemon = true }
            }
        }

        val mainHandler: Handler = Handler(Looper.getMainLooper())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
    }
}
