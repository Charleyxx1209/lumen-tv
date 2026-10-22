package app.orchard.tv.history

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import app.orchard.tv.wallpaper.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** One title the owner was watching, as the TV's own launcher recorded it. */
data class HistoryItem(
    val id: String,
    val packageName: String,
    val title: String,
    val coverUrl: String?,
    val positionMs: Int,
    val durationMs: Int,
    val receivedAt: Long,
    private val srcApp: String,
    private val extras: Map<String, String?>,
) {
    /**
     * The intent TCL's launcher (com.tcl.cyberui, HisFavClickAction.showVideo) sends to resume this
     * title: the source app's action, the history row copied into extras, aimed at the package.
     */
    fun resumeIntent(): Intent = Intent(srcApp)
        .setPackage(packageName)
        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .apply {
            this@HistoryItem.extras.forEach { (k, v) -> if (v != null) putExtra(k, v) }
            putExtra("currentPosition", positionMs)
            putExtra("duration", durationMs)
        }
}

/**
 * Watch history kept by TCL's China-market launcher (com.tcl.cyberui). Video apps report what is
 * played to it; its provider is exported with no read permission. Verified on TCL 65V8L
 * (tc8000_ay30a2, Android 11), where 哔哩哔哩 writes to it.
 */
object TclHistory {
    private const val TAG = "LumenHistory"
    private val URI = Uri.parse("content://com.tcl.cyberui.historyVideofavourites/HistoryTableName")
    private val STRING_EXTRAS = listOf("startUpMode", "videoId", "episodeId", "cmdstring", "cmdInfo", "definition", "userKey")

    /** The latest titles, newest first, one per video, only for apps still installed. */
    suspend fun load(context: Context, limit: Int = 6): List<HistoryItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val rows = runCatching {
            context.contentResolver.query(URI, null, null, null, null)?.use { c ->
                fun s(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
                fun i(name: String) = s(name)?.toIntOrNull() ?: 0
                buildList {
                    while (c.moveToNext()) {
                        val pkg = s("packageName") ?: continue
                        val src = s("srcApp") ?: continue
                        val title = (s("videoName") ?: s("episodeName"))?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        add(HistoryItem(
                            id = "$pkg/${s("videoId")}",
                            packageName = pkg,
                            title = title,
                            coverUrl = s("videoImgUrl")?.takeIf { it.startsWith("http") },
                            positionMs = i("currentPosition"),
                            durationMs = i("duration"),
                            receivedAt = s("receivedtime")?.toLongOrNull() ?: 0L,
                            srcApp = src,
                            extras = STRING_EXTRAS.associateWith(::s),
                        ))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "No TCL history: $it") }.getOrNull().orEmpty()
        rows.sortedByDescending { it.receivedAt }
            .distinctBy { it.id }
            .filter { runCatching { pm.getPackageInfo(it.packageName, 0) }.isSuccess }
            .take(limit)
    }

    private val covers = HashMap<String, Bitmap>()

    /** The cover, cut to fill a [w]×[h] screen. Kept for the session; null when it cannot be had. */
    suspend fun cover(url: String, w: Int, h: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(covers) { covers[url] }?.let { return@withContext it }
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            Images.cover(raw, w, h).also { if (it !== raw) raw.recycle() }
        }.onFailure { Log.w(TAG, "Cover failed: $it") }.getOrNull()
            ?.also { synchronized(covers) { covers[url] = it } }
    }
}
