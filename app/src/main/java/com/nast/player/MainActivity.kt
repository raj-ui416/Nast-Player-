package com.nast.player

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val audioUri get() = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    private fun perm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun granted() = checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = serve(r)
        }
        if (!granted()) requestPermissions(arrayOf(perm()), 1)
        web.loadUrl("https://nast.local/index.html")
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        web.post { web.evaluateJavascript("window.nastScan&&nastScan()", null) }
    }

    inner class Bridge {
        @JavascriptInterface
        fun scan(): String {
            if (!granted()) return "NOPERM"
            val arr = JSONArray()
            val cols = mutableListOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.Audio.Media.GENRE)
            contentResolver.query(audioUri, cols.toTypedArray(),
                "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000",
                null, null)?.use { c ->
                while (c.moveToNext()) {
                    fun s(i: Int, d: String) = (c.getString(i) ?: d).let { if (it == "<unknown>" || it.isBlank()) d else it }
                    arr.put(JSONObject()
                        .put("id", c.getLong(0))
                        .put("title", s(1, "Unknown"))
                        .put("artist", s(2, "Unknown Artist"))
                        .put("album", s(3, "Unknown Album"))
                        .put("added", c.getLong(4))
                        .put("genre", if (cols.size > 5) s(5, "Unknown") else "Unknown"))
                }
            }
            return arr.toString()
        }
    }

    private fun serve(r: WebResourceRequest): WebResourceResponse? {
        val u = r.url
        if (u.host != "nast.local") return null
        val p = u.path ?: return null
        return try {
            when {
                p.startsWith("/audio/") -> audio(p.substringAfterLast('/').toLong(), r.requestHeaders["Range"])
                p.startsWith("/art/") -> art(p.substringAfterLast('/').toLong())
                else -> WebResourceResponse("text/html", "utf-8", assets.open("index.html"))
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun audio(id: Long, range: String?): WebResourceResponse {
        val uri = ContentUris.withAppendedId(audioUri, id)
        val afd = contentResolver.openAssetFileDescriptor(uri, "r")!!
        val total = if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
        val mime = contentResolver.getType(uri) ?: "audio/mpeg"
        val ins = afd.createInputStream()
        val h = HashMap<String, String>()
        h["Accept-Ranges"] = "bytes"
        if (range != null && range.startsWith("bytes=")) {
            val pr = range.substring(6).split("-")
            val start = pr[0].toLongOrNull() ?: 0L
            val end = pr.getOrNull(1)?.toLongOrNull() ?: (total - 1)
            var left = start
            while (left > 0) { val k = ins.skip(left); if (k <= 0) break; left -= k }
            h["Content-Range"] = "bytes $start-$end/$total"
            h["Content-Length"] = (end - start + 1).toString()
            return WebResourceResponse(mime, null, 206, "Partial Content", h, ins)
        }
        h["Content-Length"] = total.toString()
        return WebResourceResponse(mime, null, 200, "OK", h, ins)
    }

    private fun art(id: Long): WebResourceResponse {
        if (Build.VERSION.SDK_INT < 29) throw Exception("no art")
        val bm = contentResolver.loadThumbnail(ContentUris.withAppendedId(audioUri, id), Size(320, 320), null)
        val bo = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 85, bo)
        return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bo.toByteArray()))
    }
}
