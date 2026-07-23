package com.example.silverageassistant.data.usage

import android.content.Context
import java.time.ZoneId

data class UsageTimeZone(
    val id: String,
    val source: String,
)

class LocationTimeZoneStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun saveLocationTimeZone(timeZone: String) {
        val normalized = runCatching { ZoneId.of(timeZone).id }.getOrNull() ?: return
        preferences.edit()
            .putString(KEY_LOCATION_TIME_ZONE, normalized)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun current(): UsageTimeZone {
        val locationTimeZone = preferences.getString(KEY_LOCATION_TIME_ZONE, null)
            ?.let { runCatching { ZoneId.of(it).id }.getOrNull() }
        return if (locationTimeZone != null) {
            UsageTimeZone(locationTimeZone, SOURCE_LOCATION)
        } else {
            UsageTimeZone(ZoneId.systemDefault().id, SOURCE_SYSTEM_FALLBACK)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "silverage_location_timezone"
        const val KEY_LOCATION_TIME_ZONE = "location_time_zone"
        const val KEY_UPDATED_AT = "updated_at_epoch_millis"
        const val SOURCE_LOCATION = "LOCATION"
        const val SOURCE_SYSTEM_FALLBACK = "SYSTEM_FALLBACK"
    }
}
