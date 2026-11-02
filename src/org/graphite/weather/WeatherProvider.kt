package org.graphite.weather

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlin.math.roundToInt

/**
 * The current weather for SystemUI, which alone can read this (see the manifest). Empty while
 * weather is off or the data has expired. The columns are the keys of SystemUI's WeatherData.
 */
class WeatherProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("description", "state", "use_celsius", "temperature"))
        val context = context ?: return cursor
        val store = WeatherStore(context)
        val weather = store.currentWeather(System.currentTimeMillis()) ?: return cursor
        val useCelsius = store.useCelsius()
        val temperature = if (useCelsius) weather.celsius else weather.celsius * 9 / 5 + 32
        cursor.addRow(
            arrayOf<Any>(
                context.getString(weather.condition.label),
                weather.stateIcon,
                if (useCelsius) 1 else 0,
                temperature.roundToInt(),
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val URI: Uri = Uri.parse("content://org.graphite.weather.data/current")

        fun notifyChanged(context: Context) {
            context.contentResolver.notifyChange(URI, null)
        }
    }
}
