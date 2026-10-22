package app.orchard.tv.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.BuildConfig
import app.orchard.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer Lumen is out. Every few hours Lumen reads the latest version number from its
 * repository (version.txt, through jsDelivr's CDN, which is reachable in China, else GitHub);
 * Control Center then names the new version, and Home says so once. Nothing is downloaded or
 * installed in the background: TCL takes installs only through 多屏互动, with an Agree on the TV.
 */
object Updates {
    private const val TAG = "LumenUpdates"
    // Not "updates": builds up to versionCode 41 kept Aerial's latest version (1.26) there, read
    // from Aerial's site, which then showed as a newer Lumen (TCL 65V8L, 2026-10-22).
    private const val PREFS = "lumen_updates"
    private val VERSION_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/Charleyxx1209/lumen-tv@main/version.txt",
        "https://raw.githubusercontent.com/Charleyxx1209/lumen-tv/main/version.txt",
    )
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L

    /** This build's version, e.g. "1.0" (the "-lumen" and "-guard" left off). */
    val current: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** The newer release's version, or null when this is the latest (or nothing is known yet). */
    var available by mutableStateOf<String?>(null); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        available = prefs.getString("latest", null)?.takeIf { newer(it, current) }
    }

    fun checkIfStale() {
        if (!started) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_MS || now - prefs.getLong("checked_at", 0) < CHECK_EVERY_MS) return
        lastAttempt = now
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                VERSION_URLS.firstNotNullOfOrNull { url -> runCatching { read(url).trim().removePrefix("v") }.getOrNull() }
                    .let { if (it == null) Result.failure(IllegalStateException("no version source answered")) else Result.success(it) }
                    .onFailure { Log.w(TAG, "could not check for a new version", it) }
                    .getOrNull()
                    ?.takeIf { it.matches(Regex("\\d+(\\.\\d+)*")) }
            } ?: return@launch
            prefs.edit { putString("latest", latest); putLong("checked_at", System.currentTimeMillis()) }
            available = latest.takeIf { newer(it, current) }
            // Said once on Home for each new version; Control Center keeps saying it.
            available?.takeIf { prefs.getString("announced", null) != it }?.let { v ->
                prefs.edit { putString("announced", v) }
                android.widget.Toast.makeText(app, app.getString(R.string.update_toast, v), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Whether version [a] comes after [b], comparing dotted numbers ("1.10" after "1.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
