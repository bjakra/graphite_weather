package org.graphite.weather

import androidx.annotation.StringRes

/** Current conditions, in the small set the lock screen can show. */
data class Weather(val celsius: Double, val condition: Condition, val isDay: Boolean) {
    /** A value of SystemUI's WeatherData.WeatherStateIcon. */
    val stateIcon: Int get() = if (isDay) condition.dayIcon else condition.nightIcon
}

enum class Condition(@StringRes val label: Int, val dayIcon: Int, val nightIcon: Int) {
    CLEAR(R.string.weather_clear, 1, 2),
    MOSTLY_CLEAR(R.string.weather_mostly_clear, 3, 4),
    PARTLY_CLOUDY(R.string.weather_partly_cloudy, 5, 6),
    CLOUDY(R.string.weather_cloudy, 9, 9),
    FOG(R.string.weather_fog, 10, 10),
    DRIZZLE(R.string.weather_drizzle, 11, 11),
    RAIN(R.string.weather_rain, 13, 13),
    HEAVY_RAIN(R.string.weather_heavy_rain, 12, 12),
    SHOWERS(R.string.weather_showers, 14, 15),
    SNOW(R.string.weather_snow, 21, 21),
    HEAVY_SNOW(R.string.weather_heavy_snow, 22, 22),
    THUNDERSTORM(R.string.weather_thunderstorm, 18, 18),
}
