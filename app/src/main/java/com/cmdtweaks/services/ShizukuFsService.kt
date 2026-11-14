package com.cmdtweaks.services

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlin.system.exitProcess

class ShizukuFsService() : Binder() {
    private val remoteFsBinder: IBinder = FileSystemManager.getService()

    @Suppress("unused")
    constructor(context: Context) : this()

    override fun getInterfaceDescriptor(): String? {
        return remoteFsBinder.interfaceDescriptor
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(remoteFsBinder.interfaceDescriptor ?: "android.os.IBinder")
            return true
        }
        if (code == 16777115) {
            exitProcess(0)
        }
        return remoteFsBinder.transact(code, data, reply, flags)
    }
}
