package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean)

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else a guess from the network's public address.
 * The guess is often wrong behind a proxy (a whole home behind one shows another country), which
 * is why the phone page shows it and lets the owner correct it.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = WeatherNow(prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true))
        }
    }

    private var failed = false

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            }
            prefs.getString("place", null)?.let { place = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                }
            }
        }
    }

    /** Off the main thread. */
    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            // ipinfo.io: no key, and its "loc" is "lat,lon".
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            lat = loc[0].toFloat()
            lon = loc[1].toFloat()
            prefs.edit {
                putFloat("lat", lat); putFloat("lon", lon)
                putString("place", ip.optString("city").ifBlank { null }?.let(::localName))
                putLong("located_at", System.currentTimeMillis())
            }
        }
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&timezone=auto"))
        val current = json.getJSONObject("current")
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
        )
    }

    /**
     * Names the place (from the phone page), looked up in Open-Meteo's geocoder, and fetches its
     * weather at once. False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(name.trim(), "UTF-8")
                JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=zh&format=json"))
                    .optJSONArray("results")?.optJSONObject(0)
            }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.getDouble("latitude").toFloat())
            putFloat("lon", hit.getDouble("longitude").toFloat())
            putString("place", hit.optString("name").ifBlank { name.trim() })
            putBoolean("chosen", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    /** ipinfo names cities in English ("Shanghai"); Open-Meteo's geocoder knows the Chinese name. */
    private fun localName(city: String): String = runCatching {
        val q = java.net.URLEncoder.encode(city, "UTF-8")
        JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=zh&format=json"))
            .getJSONArray("results").getJSONObject(0).optString("name").ifBlank { city }
    }.getOrDefault(city)

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain // drizzle, rain and showers: 51..67, 80..82
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String): String = try {
        read(url)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"))
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
