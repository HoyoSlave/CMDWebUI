package com.cmdtweaks.services

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.UserManager
import com.cmdtweaks.IKsuWebuiStandaloneInterface
import com.topjohnwu.superuser.ipc.RootService
import rikka.parcelablelist.ParcelableListSlice
import kotlin.system.exitProcess

class PackageQueryService : RootService() {
    override fun onBind(intent: Intent): IBinder {
        return PackageBinder(this)
    }
}

class ShizukuPackageBinder() : IKsuWebuiStandaloneInterface.Stub() {
    private var context: Context? = null

    @Suppress("unused")
    constructor(ctx: Context) : this() {
        this.context = ctx
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
        return ParcelableListSlice(PackageInspector.queryAllUsers(context, flags))
    }
}

class PackageBinder(private val context: Context?) : IKsuWebuiStandaloneInterface.Stub() {
    override fun destroy() = Unit

    override fun getPackages(flags: Int): ParcelableListSlice<PackageInfo> {
        return ParcelableListSlice(PackageInspector.queryAllUsers(context, flags))
    }
}

internal object PackageInspector {
    fun queryAllUsers(context: Context?, flags: Int): ArrayList<PackageInfo> {
        val output = ArrayList<PackageInfo>()
        val users = runCatching {
            val um = context?.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.userProfiles?.map { it.hashCode() }?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: listOf(0)

        for (userId in users) {
            output.addAll(queryUserPackages(context, flags, userId))
        }
        return output
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryUserPackages(context: Context?, flags: Int, userId: Int): List<PackageInfo> {
        if (context != null) {
            runCatching {
                val pm = context.packageManager
                val method = pm.javaClass.getDeclaredMethod(
                    "getInstalledPackagesAsUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                return method.invoke(pm, flags, userId) as List<PackageInfo>
            }
        }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val ipm = at.getDeclaredMethod("getPackageManager").invoke(null) ?: return emptyList()
            val getter = ipm.javaClass.methods.firstOrNull {
                it.name == "getInstalledPackages" && it.parameterTypes.size == 2
            } ?: return emptyList()

            val slice = if (getter.parameterTypes[0] == Long::class.javaPrimitiveType) {
                getter.invoke(ipm, flags.toLong(), userId)
            } else {
                getter.invoke(ipm, flags, userId)
            } ?: return emptyList()

            slice.javaClass.getMethod("getList").invoke(slice) as? List<PackageInfo> ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
