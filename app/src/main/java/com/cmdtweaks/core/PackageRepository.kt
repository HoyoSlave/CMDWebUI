package com.cmdtweaks.core

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.util.concurrent.ConcurrentHashMap

object PackageRepository {
    private const val ICON_CACHE_MAX = 200
    @Volatile
    private var packageCache: Map<String, PackageInfo>? = null
    private val labelCache = ConcurrentHashMap<String, String>()
    private val iconCache = LruCache<String, Bitmap>(ICON_CACHE_MAX)

    fun warmUp(context: Context) {
        getPackagesMap(context)
    }

    fun getPackagesMap(context: Context): Map<String, PackageInfo> {
        packageCache?.let { if (it.isNotEmpty()) return it }
        return synchronized(this) {
            packageCache?.let { if (it.isNotEmpty()) return it }
            val pm = context.packageManager
            val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            val map = HashMap<String, PackageInfo>(installed.size)
            for (pkg in installed) {
                map[pkg.packageName] = pkg
            }
            if (map.isNotEmpty()) {
                packageCache = map
            }
            map
        }
    }

    fun findPackageInfo(context: Context, packageName: String): PackageInfo? {
        getPackagesMap(context)[packageName]?.let { return it }
        return runCatching { context.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
    }

    fun resolveLabel(context: Context, pkg: PackageInfo): String {
        return labelCache.getOrPut(pkg.packageName) {
            runCatching {
                val pm = context.packageManager
                val appInfo = pkg.applicationInfo ?: pm.getApplicationInfo(pkg.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(pkg.packageName)
        }
    }

    @Synchronized
    fun loadIconBitmap(context: Context, packageName: String, sizePx: Int): Bitmap? {
        iconCache.get(packageName)?.let { return it }
        return try {
            val pm = context.packageManager
            val pkg = findPackageInfo(context, packageName)
            val drawable = runCatching {
                val appInfo = pkg?.applicationInfo ?: pm.getApplicationInfo(packageName, 0)
                appInfo.loadIcon(pm)
            }.getOrElse {
                runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            } ?: return null

            val rendered = renderDrawable(drawable, sizePx).scale(sizePx, sizePx)
            iconCache.put(packageName, rendered)
            rendered
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderDrawable(drawable: Drawable, fallbackSize: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else fallbackSize
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else fallbackSize
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
