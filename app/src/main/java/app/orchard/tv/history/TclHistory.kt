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
    /** How far in, 0..1; null when the app did not say. */
    val progress: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    /** Whole minutes left, at least 1; null when unknown. */
    val minutesLeft: Int? get() = if (durationMs > 0) ((durationMs - positionMs) / 60_000).coerceAtLeast(1) else null

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
                        val title = (s("videoName") ?: s("episodeName"))?.let(::cleanTitle)?.takeIf { it.isNotEmpty() } ?: continue
                        add(HistoryItem(
                            id = "$pkg/${s("videoId")}",
                            packageName = pkg,
                            title = title,
                            coverUrl = s("videoImgUrl")?.takeIf { it.startsWith("http") }?.let(::fullSize),
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
            // Watched to the end: nothing to resume (tvOS drops these from Up Next too).
            .filterNot { it.durationMs > 0 && it.positionMs >= it.durationMs * 0.97f }
            .filter { runCatching { pm.getPackageInfo(it.packageName, 0) }.isSuccess }
            .take(limit)
    }

    /**
     * Titles as the apps wrote them, made fit for a TV: emoji and pictographs go (the TV's fonts
     * draw many as tofu, and tvOS titles carry none), as do the "??" left where an app stored an
     * emoji its database could not hold ("Vlog??30+…" from Bilibili), stray format characters, and
     * doubled spaces.
     */
    internal fun cleanTitle(raw: String): String = raw
        .replace(Regex("\\?{2,}"), " ")
        .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200B}-\\x{200F}\\x{2060}-\\x{206F}\\x{E0000}-\\x{E007F}\\x{FFFD}\\x{20E3}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('|', '-', '·', '丨')
        .trim()

    /**
     * Tencent records its poster at thumbnail size (".../vcover_vt_pic/0/<id>/260", 260×364);
     * the same path ending in /0 is the original (1280×1796). Checked 2026-10-22.
     */
    private fun fullSize(url: String): String =
        if ("qpic.cn/vcover_" in url) url.replace(Regex("/\\d+$"), "/0") else url

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
            if (raw.height > raw.width) posterScene(raw, w, h).also { raw.recycle() }
            else Images.cover(raw, w, h).also { if (it !== raw) raw.recycle() }
        }.onFailure { Log.w(TAG, "Cover failed: $it") }.getOrNull()
            ?.also { synchronized(covers) { covers[url] = it } }
    }

    /**
     * A portrait poster (Tencent) cropped to fill a wide screen would lose most of it. Instead the
     * poster, blurred and dimmed, fills the screen, and the poster itself stands on the right,
     * below the top bar and above the dock, clear of the title on the left.
     */
    private fun posterScene(poster: Bitmap, w: Int, h: Int): Bitmap {
        val frosted = Images.frost(Images.cover(poster, 320, 180))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(frosted, null, android.graphics.Rect(0, 0, w, h), paint)
        canvas.drawColor(android.graphics.Color.argb(70, 0, 0, 0))
        frosted.recycle()
        // Between the top bar (to ~12% of the height) and the page dots over the dock (~63%).
        val ph = h * 0.5f
        val pw = ph * poster.width / poster.height
        val left = w * 0.885f - pw
        val top = h * 0.155f
        val dst = android.graphics.RectF(left, top, left + pw, top + ph)
        val r = h * 0.022f
        paint.setShadowLayer(h * 0.03f, 0f, h * 0.012f, android.graphics.Color.argb(120, 0, 0, 0))
        canvas.drawRoundRect(dst, r, r, paint.apply { color = android.graphics.Color.BLACK })
        paint.clearShadowLayer()
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(dst, r, r, android.graphics.Path.Direction.CW) })
        canvas.drawBitmap(poster, null, dst, paint)
        canvas.restore()
        return out
    }
}
