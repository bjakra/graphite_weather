package org.graphite.weather

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONException
import org.json.JSONObject

/**
 * Fetches weather over HTTPS. Responses are untrusted: their size is limited, and anything
 * unexpected or out of range is an error rather than something to show. Requests carry no
 * identifiers, cookies or cache.
 */
object WeatherClient {
    const val PROVIDER_OPEN_METEO = "open_meteo"
    const val PROVIDER_MET_NORWAY = "met_norway"

    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val TIMEOUT_MILLIS = 15_000

    // MET Norway's terms require an identifying User-Agent
    private const val USER_AGENT = "GraphiteWeather/1.0 github.com/bjakra"

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    /** Tries the chosen provider, then the other one. */
    fun fetch(preferred: String, latitude: Double, longitude: Double): Weather {
        val order = if (preferred == PROVIDER_MET_NORWAY) {
            listOf(PROVIDER_MET_NORWAY, PROVIDER_OPEN_METEO)
        } else {
            listOf(PROVIDER_OPEN_METEO, PROVIDER_MET_NORWAY)
        }
        var failure: IOException? = null
        for (provider in order) {
            try {
                return if (provider == PROVIDER_OPEN_METEO) {
                    openMeteo(latitude, longitude)
                } else {
                    metNorway(latitude, longitude)
                }
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure!!
    }

    private fun openMeteo(latitude: Double, longitude: Double): Weather = parsing {
        val current = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day",
            ),
        ).getJSONObject("current")
        Weather(
            checkedTemperature(current.getDouble("temperature_2m")),
            wmoCondition(current.getInt("weather_code")),
            current.getInt("is_day") == 1,
        )
    }

    private fun metNorway(latitude: Double, longitude: Double): Weather = parsing {
        val data = JSONObject(
            get("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$latitude&lon=$longitude"),
        ).getJSONObject("properties").getJSONArray("timeseries").getJSONObject(0).getJSONObject("data")
        val symbol = data.getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code")
        Weather(
            checkedTemperature(data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature")),
            symbolCondition(symbol),
            !symbol.endsWith("_night"),
        )
    }

    /** City search. Only [query] leaves the phone, and only when the user searches. */
    fun searchPlaces(query: String): List<Place> = parsing {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=5"))
        val results = json.optJSONArray("results") ?: return@parsing emptyList<Place>()
        (0 until results.length()).map { index ->
            val place = results.getJSONObject(index)
            val region = listOf(place.optString("admin1"), place.optString("country"))
                .filter { it.isNotEmpty() }.joinToString(", ")
            Place(
                if (region.isEmpty()) place.getString("name") else "${place.getString("name")}, $region",
                checkedRange(place.getDouble("latitude"), 90.0),
                checkedRange(place.getDouble("longitude"), 180.0),
            )
        }
    }

    private inline fun <T> parsing(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("Unexpected response", e)
    }

    private fun checkedTemperature(value: Double): Double {
        if (value !in -100.0..70.0) throw IOException("Temperature out of range")
        return value
    }

    private fun checkedRange(value: Double, limit: Double): Double {
        if (value !in -limit..limit) throw IOException("Coordinate out of range")
        return value
    }

    private fun wmoCondition(code: Int): Condition = when (code) {
        0 -> Condition.CLEAR
        1 -> Condition.MOSTLY_CLEAR
        2 -> Condition.PARTLY_CLOUDY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        51, 53, 55, 56, 57 -> Condition.DRIZZLE
        61, 63, 66 -> Condition.RAIN
        65, 67 -> Condition.HEAVY_RAIN
        80, 81, 82 -> Condition.SHOWERS
        71, 73, 77, 85 -> Condition.SNOW
        75, 86 -> Condition.HEAVY_SNOW
        95, 96, 99 -> Condition.THUNDERSTORM
        else -> throw IOException("Unknown weather code")
    }

    private fun symbolCondition(symbol: String): Condition {
        val name = symbol.substringBefore('_').lowercase()
        return when {
            "thunder" in name -> Condition.THUNDERSTORM
            name == "clearsky" -> Condition.CLEAR
            name == "fair" -> Condition.MOSTLY_CLEAR
            name == "partlycloudy" -> Condition.PARTLY_CLOUDY
            name == "cloudy" -> Condition.CLOUDY
            name == "fog" -> Condition.FOG
            "heavysnow" in name -> Condition.HEAVY_SNOW
            "snow" in name || "sleet" in name -> Condition.SNOW
            "showers" in name -> Condition.SHOWERS
            "heavyrain" in name -> Condition.HEAVY_RAIN
            "lightrain" in name -> Condition.DRIZZLE
            "rain" in name -> Condition.RAIN
            else -> throw IOException("Unknown weather symbol")
        }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > MAX_RESPONSE_BYTES) throw IOException("Response too large")
                    out.write(buffer, 0, read)
                }
            }
            return out.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }
}
