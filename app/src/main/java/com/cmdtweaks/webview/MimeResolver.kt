package com.cmdtweaks.webview

import java.net.URLConnection

object MimeResolver {
    private const val FALLBACK_MIME = "text/plain"

    fun resolve(filePath: String?): String {
        if (filePath.isNullOrEmpty()) return FALLBACK_MIME
        URLConnection.guessContentTypeFromName(filePath)?.let { return it }

        val dotIdx = filePath.lastIndexOf('.')
        if (dotIdx == -1) return FALLBACK_MIME

        return when (filePath.substring(dotIdx + 1).lowercase()) {
            "html", "htm", "shtml", "shtm", "ehtml" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "svg", "svgz" -> "image/svg+xml"
            "png" -> "image/png"
            "apng" -> "image/apng"
            "jpg", "jpeg", "jfif", "pjpeg", "pjp" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "woff" -> "application/font-woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "wasm" -> "application/wasm"
            "xml" -> "text/xml"
            "xhtml", "xht", "xhtm" -> "application/xhtml+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/x-m4a"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mpeg", "mpg" -> "video/mpeg"
            "ogv", "ogm" -> "video/ogg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "gz", "tgz" -> "application/gzip"
            "mht", "mhtml" -> "multipart/related"
            else -> FALLBACK_MIME
        }
    }
}
