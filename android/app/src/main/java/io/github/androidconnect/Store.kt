package io.github.androidconnect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A laptop this tablet has paired with. [fingerprint] pins its TLS certificate. */
data class Laptop(
    val serverId: String,
    val name: String,
    val fingerprint: String,
    val token: String,
    val host: String,
    val port: Int,
)

data class Settings(
    val fps: Int = 60,
    val quality: String = "balanced",   // low | balanced | high
    val resolution: String = "native",  // native | fhd
    val showStats: Boolean = false,
    val touchMode: String = "touch",    // touch | mouse
)

/** Persistent state in private app storage (excluded from backups). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("androidconnect", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }

    private val _laptops = MutableStateFlow(loadLaptops())
    val laptops: StateFlow<List<Laptop>> = _laptops

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    var lastServerId: String?
        get() = prefs.getString("last_server", null)
        set(value) = prefs.edit().putString("last_server", value).apply()

    fun laptop(serverId: String?) = _laptops.value.firstOrNull { it.serverId == serverId }

    fun laptopByFingerprint(fp: String) = _laptops.value.firstOrNull { it.fingerprint == fp }

    @Synchronized
    fun save(laptop: Laptop) {
        _laptops.value = _laptops.value.filter { it.serverId != laptop.serverId } + laptop
        persistLaptops()
    }

    @Synchronized
    fun remove(serverId: String) {
        _laptops.value = _laptops.value.filter { it.serverId != serverId }
        if (lastServerId == serverId) lastServerId = null
        persistLaptops()
    }

    fun updateSettings(settings: Settings) {
        _settings.value = settings
        prefs.edit()
            .putInt("fps", settings.fps)
            .putString("quality", settings.quality)
            .putString("resolution", settings.resolution)
            .putBoolean("show_stats", settings.showStats)
            .putString("touch_mode", settings.touchMode)
            .apply()
    }

    private fun loadSettings() = Settings(
        fps = prefs.getInt("fps", 60),
        quality = prefs.getString("quality", "balanced")!!,
        resolution = prefs.getString("resolution", "native")!!,
        showStats = prefs.getBoolean("show_stats", false),
        touchMode = prefs.getString("touch_mode", "touch")!!,
    )

    private fun loadLaptops(): List<Laptop> {
        val raw = prefs.getString("laptops", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Laptop(o.getString("id"), o.getString("name"), o.getString("fp"), o.getString("token"),
                    o.getString("host"), o.getInt("port"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persistLaptops() {
        val arr = JSONArray()
        for (l in _laptops.value) {
            arr.put(JSONObject().put("id", l.serverId).put("name", l.name).put("fp", l.fingerprint)
                .put("token", l.token).put("host", l.host).put("port", l.port))
        }
        prefs.edit().putString("laptops", arr.toString()).apply()
    }
}
