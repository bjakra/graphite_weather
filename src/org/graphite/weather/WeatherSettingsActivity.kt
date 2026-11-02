package org.graphite.weather

import android.os.Bundle
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarAppCompatActivity

class WeatherSettingsActivity : CollapsingToolbarAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, WeatherSettingsFragment())
                .commit()
        }
    }
}
