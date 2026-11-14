package com.cmdtweaks.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

object MonetCssEngine {
    private val cachedCss = AtomicReference("")

    val currentCss: String
        get() = cachedCss.get()

    fun refresh(context: Context) {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val palette = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun hex(@ColorRes id: Int): String = ContextCompat.getColor(context, id).toRgbaHex()

            if (isDark) {
                mapOf(
                    "primary" to hex(android.R.color.system_accent1_200),
                    "onPrimary" to hex(android.R.color.system_accent1_800),
                    "primaryContainer" to hex(android.R.color.system_accent1_700),
                    "onPrimaryContainer" to hex(android.R.color.system_accent1_100),
                    "inversePrimary" to hex(android.R.color.system_accent1_600),
                    "secondary" to hex(android.R.color.system_accent2_200),
                    "onSecondary" to hex(android.R.color.system_accent2_800),
                    "secondaryContainer" to hex(android.R.color.system_accent2_700),
                    "onSecondaryContainer" to hex(android.R.color.system_accent2_100),
                    "tertiary" to hex(android.R.color.system_accent3_200),
                    "onTertiary" to hex(android.R.color.system_accent3_800),
                    "tertiaryContainer" to hex(android.R.color.system_accent3_700),
                    "onTertiaryContainer" to hex(android.R.color.system_accent3_100),
                    "background" to hex(android.R.color.system_neutral1_900),
                    "onBackground" to hex(android.R.color.system_neutral1_100),
                    "surface" to hex(android.R.color.system_neutral1_900),
                    "tonalSurface" to hex(android.R.color.system_neutral1_800),
                    "onSurface" to hex(android.R.color.system_neutral1_100),
                    "surfaceVariant" to hex(android.R.color.system_neutral2_700),
                    "onSurfaceVariant" to hex(android.R.color.system_neutral2_200),
                    "surfaceTint" to hex(android.R.color.system_accent1_200),
                    "inverseSurface" to hex(android.R.color.system_neutral1_100),
                    "inverseOnSurface" to hex(android.R.color.system_neutral1_800),
                    "error" to "#F2B8B5FF",
                    "onError" to "#601410FF",
                    "errorContainer" to "#8C1D18FF",
                    "onErrorContainer" to "#F9DEDCFF",
                    "outline" to hex(android.R.color.system_neutral2_400),
                    "outlineVariant" to hex(android.R.color.system_neutral2_700),
                    "scrim" to "#000000FF",
                    "surfaceBright" to hex(android.R.color.system_neutral1_700),
                    "surfaceDim" to hex(android.R.color.system_neutral1_900),
                    "surfaceContainer" to hex(android.R.color.system_neutral1_800),
                    "surfaceContainerHigh" to hex(android.R.color.system_neutral1_700),
                    "surfaceContainerHighest" to hex(android.R.color.system_neutral1_600),
                    "surfaceContainerLow" to hex(android.R.color.system_neutral1_900),
                    "surfaceContainerLowest" to hex(android.R.color.system_neutral1_1000),
                    "filledTonalButtonContentColor" to hex(android.R.color.system_accent2_100),
                    "filledTonalButtonContainerColor" to hex(android.R.color.system_accent2_700),
                    "filledTonalButtonDisabledContentColor" to hex(android.R.color.system_neutral2_200),
                    "filledTonalButtonDisabledContainerColor" to hex(android.R.color.system_neutral2_700),
                    "filledCardContentColor" to hex(android.R.color.system_accent1_100),
                    "filledCardContainerColor" to hex(android.R.color.system_accent1_700),
                    "filledCardDisabledContentColor" to hex(android.R.color.system_neutral2_200),
                    "filledCardDisabledContainerColor" to hex(android.R.color.system_neutral2_700)
                )
            } else {
                mapOf(
                    "primary" to hex(android.R.color.system_accent1_600),
                    "onPrimary" to hex(android.R.color.system_accent1_0),
                    "primaryContainer" to hex(android.R.color.system_accent1_100),
                    "onPrimaryContainer" to hex(android.R.color.system_accent1_900),
                    "inversePrimary" to hex(android.R.color.system_accent1_200),
                    "secondary" to hex(android.R.color.system_accent2_600),
                    "onSecondary" to hex(android.R.color.system_accent2_0),
                    "secondaryContainer" to hex(android.R.color.system_accent2_100),
                    "onSecondaryContainer" to hex(android.R.color.system_accent2_900),
                    "tertiary" to hex(android.R.color.system_accent3_600),
                    "onTertiary" to hex(android.R.color.system_accent3_0),
                    "tertiaryContainer" to hex(android.R.color.system_accent3_100),
                    "onTertiaryContainer" to hex(android.R.color.system_accent3_900),
                    "background" to hex(android.R.color.system_neutral1_10),
                    "onBackground" to hex(android.R.color.system_neutral1_900),
                    "surface" to hex(android.R.color.system_neutral1_10),
                    "tonalSurface" to hex(android.R.color.system_neutral1_50),
                    "onSurface" to hex(android.R.color.system_neutral1_900),
                    "surfaceVariant" to hex(android.R.color.system_neutral2_100),
                    "onSurfaceVariant" to hex(android.R.color.system_neutral2_700),
                    "surfaceTint" to hex(android.R.color.system_accent1_600),
                    "inverseSurface" to hex(android.R.color.system_neutral1_800),
                    "inverseOnSurface" to hex(android.R.color.system_neutral1_50),
                    "error" to "#B3261EFF",
                    "onError" to "#FFFFFFFF",
                    "errorContainer" to "#F9DEDCFF",
                    "onErrorContainer" to "#410E0BFF",
                    "outline" to hex(android.R.color.system_neutral2_500),
                    "outlineVariant" to hex(android.R.color.system_neutral2_200),
                    "scrim" to "#000000FF",
                    "surfaceBright" to hex(android.R.color.system_neutral1_10),
                    "surfaceDim" to hex(android.R.color.system_neutral1_200),
                    "surfaceContainer" to hex(android.R.color.system_neutral1_50),
                    "surfaceContainerHigh" to hex(android.R.color.system_neutral1_100),
                    "surfaceContainerHighest" to hex(android.R.color.system_neutral1_200),
                    "surfaceContainerLow" to hex(android.R.color.system_neutral1_10),
                    "surfaceContainerLowest" to hex(android.R.color.system_neutral1_0),
                    "filledTonalButtonContentColor" to hex(android.R.color.system_accent2_900),
                    "filledTonalButtonContainerColor" to hex(android.R.color.system_accent2_100),
                    "filledTonalButtonDisabledContentColor" to hex(android.R.color.system_neutral2_700),
                    "filledTonalButtonDisabledContainerColor" to hex(android.R.color.system_neutral2_100),
                    "filledCardContentColor" to hex(android.R.color.system_accent1_900),
                    "filledCardContainerColor" to hex(android.R.color.system_accent1_100),
                    "filledCardDisabledContentColor" to hex(android.R.color.system_neutral2_700),
                    "filledCardDisabledContainerColor" to hex(android.R.color.system_neutral2_100)
                )
            }
        } else {
            mapOf(
                "primary" to if (isDark) "#D0BCFFFF" else "#6750A4FF",
                "onPrimary" to if (isDark) "#381E72FF" else "#FFFFFFFF",
                "primaryContainer" to if (isDark) "#4F378BFF" else "#EADDFFFF",
                "onPrimaryContainer" to if (isDark) "#EADDFFFF" else "#21005DFF",
                "background" to if (isDark) "#141218FF" else "#FEF7FFFF",
                "onBackground" to if (isDark) "#E6E0E9FF" else "#1D1B20FF",
                "surface" to if (isDark) "#141218FF" else "#FEF7FFFF",
                "onSurface" to if (isDark) "#E6E0E9FF" else "#1D1B20FF"
            )
        }

        cachedCss.set(
            buildString {
                append(":root {\n")
                for ((key, value) in palette) {
                    append("  --$key: $value;\n")
                }
                append("}\n")
            }
        )
    }

    private fun Int.toRgbaHex(): String {
        return String.format("#%06X%02X", this and 0xFFFFFF, (this ushr 24) and 0xFF)
    }
}
