package org.graphite.weather

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import kotlin.math.max
import kotlin.math.round

/** Settings and the last fetched weather, in credential encrypted storage and out of backups. */
class WeatherStore(context: Context) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var source: String
        get() = prefs.getString(KEY_SOURCE, SOURCE_MANUAL)!!
        set(value) = prefs.edit().putString(KEY_SOURCE, value).apply()

    var units: String
        get() = prefs.getString(KEY_UNITS, UNITS_SYSTEM)!!
        set(value) = prefs.edit().putString(KEY_UNITS, value).apply()

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL, 2)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value).apply()

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, WeatherClient.PROVIDER_OPEN_METEO)!!
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    val location: Location?
        get() = if (prefs.contains(KEY_LATITUDE)) {
            Location(
                prefs.getFloat(KEY_LATITUDE, 0f).toDouble(),
                prefs.getFloat(KEY_LONGITUDE, 0f).toDouble(),
                prefs.getString(KEY_LOCATION_NAME, "")!!,
            )
        } else {
            null
        }

    /** Coordinates are rounded here, so a precise position is never stored or sent. */
    fun setLocation(latitude: Double, longitude: Double, name: String) {
        prefs.edit()
            .putFloat(KEY_LATITUDE, roundCoordinate(latitude).toFloat())
            .putFloat(KEY_LONGITUDE, roundCoordinate(longitude).toFloat())
            .putString(KEY_LOCATION_NAME, name)
            .remove(KEY_FETCHED_AT)
            .apply()
    }

    fun saveWeather(weather: Weather, now: Long) {
        prefs.edit()
            .putFloat(KEY_TEMPERATURE, weather.celsius.toFloat())
            .putInt(KEY_CONDITION, weather.condition.ordinal)
            .putBoolean(KEY_DAY, weather.isDay)
            .putLong(KEY_FETCHED_AT, now)
            .apply()
    }

    fun fetchedAt(): Long = prefs.getLong(KEY_FETCHED_AT, 0)

    /** The last weather, or null if it's off, missing or too old to still be true. */
    fun currentWeather(now: Long): Weather? {
        val fetchedAt = fetchedAt()
        val maxAge = max(MIN_MAX_AGE_HOURS, intervalHours * 3) * HOUR_MILLIS
        if (!enabled || fetchedAt == 0L || now - fetchedAt !in 0..maxAge) return null
        return Weather(
            prefs.getFloat(KEY_TEMPERATURE, 0f).toDouble(),
            Condition.entries[prefs.getInt(KEY_CONDITION, 0)],
            prefs.getBoolean(KEY_DAY, true),
        )
    }

    fun useCelsius(): Boolean = when (units) {
        UNITS_CELSIUS -> true
        UNITS_FAHRENHEIT -> false
        else -> LocaleData.getMeasurementSystem(ULocale.getDefault()) !=
            LocaleData.MeasurementSystem.US
    }

    data class Location(val latitude: Double, val longitude: Double, val name: String)

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_DEVICE = "device"
        const val UNITS_SYSTEM = "system"
        const val UNITS_CELSIUS = "celsius"
        const val UNITS_FAHRENHEIT = "fahrenheit"
        private const val HOUR_MILLIS = 60L * 60 * 1000
        private const val MIN_MAX_AGE_HOURS = 6
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SOURCE = "source"
        private const val KEY_UNITS = "units"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_LOCATION_NAME = "location_name"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_CONDITION = "condition"
        private const val KEY_DAY = "day"
        private const val KEY_FETCHED_AT = "fetched_at"

        fun roundCoordinate(value: Double): Double = round(value * 100) / 100
    }
}
