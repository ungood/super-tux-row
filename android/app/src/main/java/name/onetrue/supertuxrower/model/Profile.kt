package name.onetrue.supertuxrower.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Reference 2k time; the bands are fractions of the power at this pace. */
    val twoKSeconds: Int,
    val compactOverlay: Boolean = false,
) {
    val twoKWatts: Double get() = Rowing.watts(2000.0 / twoKSeconds)

    fun fraction(watts: Double) = watts / twoKWatts

    fun band(watts: Double) = Band.forFraction(fraction(watts))

    /** Boat speed at a fraction of 2k power. */
    fun speedAt(fraction: Double) = Rowing.speed(twoKWatts * fraction)
}

enum class StartingLevel(val label: String, val twoKSeconds: Int) {
    BEGINNER("Beginner", 540),
    FIT("Fit", 480),
    STRONG("Strong", 420),
}

/** Profiles kept on the tablet, plus which one rowed last. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    var lastUsedId: String?
        get() = prefs.getString(LAST_USED, null)
        set(value) = prefs.edit().putString(LAST_USED, value).apply()

    fun load(): List<Profile> {
        val array = JSONArray(prefs.getString(PROFILES, "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Profile(o.getString("id"), o.getString("name"), o.getInt("twoKSeconds"), o.optBoolean("compactOverlay"))
        }
    }

    fun upsert(profile: Profile): List<Profile> {
        val profiles = load().toMutableList()
        val i = profiles.indexOfFirst { it.id == profile.id }
        if (i >= 0) profiles[i] = profile else profiles += profile
        save(profiles)
        return profiles
    }

    fun delete(id: String): List<Profile> {
        val profiles = load().filter { it.id != id }
        save(profiles)
        if (lastUsedId == id) lastUsedId = null
        return profiles
    }

    private fun save(profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("twoKSeconds", it.twoKSeconds)
                    .put("compactOverlay", it.compactOverlay)
            )
        }
        prefs.edit().putString(PROFILES, array.toString()).apply()
    }

    private companion object {
        const val PROFILES = "profiles"
        const val LAST_USED = "lastUsed"
    }
}
