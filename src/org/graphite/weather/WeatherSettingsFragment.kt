package org.graphite.weather

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.io.IOException
import java.util.concurrent.Executors

class WeatherSettingsFragment : PreferenceFragmentCompat() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WeatherStore
    private lateinit var location: Preference
    private lateinit var deviceUpdate: Preference
    private lateinit var source: ListPreference
    private lateinit var status: Preference
    private val handler = Handler(Looper.getMainLooper())
    private var stopLocating: () -> Unit = {}

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        store = WeatherStore(context)
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        screen.addPreference(SwitchPreferenceCompat(context).apply {
            title = getString(R.string.enabled)
            summary = getString(R.string.enabled_summary)
            isPersistent = false
            isChecked = store.enabled
            setOnPreferenceChangeListener { _, value ->
                store.enabled = value as Boolean
                settingsChanged()
                true
            }
        })
        source = ListPreference(context).apply {
            key = "source"
            title = getString(R.string.source)
            dialogTitle = title
            isPersistent = false
            entries = arrayOf(getString(R.string.source_manual), getString(R.string.source_device))
            entryValues = arrayOf(WeatherStore.SOURCE_MANUAL, WeatherStore.SOURCE_DEVICE)
            value = store.source
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                if (value == WeatherStore.SOURCE_DEVICE && store.source != WeatherStore.SOURCE_DEVICE) {
                    confirmDeviceLocation()
                    false
                } else {
                    store.source = value as String
                    updateVisibility()
                    true
                }
            }
        }
        screen.addPreference(source)
        location = Preference(context).apply {
            title = getString(R.string.location)
            isPersistent = false
            setOnPreferenceClickListener {
                askForLocation()
                true
            }
        }
        screen.addPreference(location)
        deviceUpdate = Preference(context).apply {
            title = getString(R.string.device_update)
            summary = getString(R.string.device_update_summary)
            isPersistent = false
            setOnPreferenceClickListener {
                updateDeviceLocation()
                true
            }
        }
        screen.addPreference(deviceUpdate)
        screen.addPreference(ListPreference(context).apply {
            key = "units"
            title = getString(R.string.units)
            dialogTitle = title
            isPersistent = false
            entries = arrayOf(
                getString(R.string.units_system), getString(R.string.units_celsius), getString(R.string.units_fahrenheit),
            )
            entryValues = arrayOf(WeatherStore.UNITS_SYSTEM, WeatherStore.UNITS_CELSIUS, WeatherStore.UNITS_FAHRENHEIT)
            value = store.units
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                store.units = value as String
                WeatherProvider.notifyChanged(requireContext())
                true
            }
        })
        val hours = listOf(1, 2, 4, 6)
        screen.addPreference(ListPreference(context).apply {
            key = "interval"
            title = getString(R.string.interval)
            dialogTitle = title
            isPersistent = false
            entries = hours.map {
                if (it == 1) getString(R.string.interval_hour) else getString(R.string.interval_hours, it)
            }.toTypedArray()
            entryValues = hours.map { it.toString() }.toTypedArray()
            value = store.intervalHours.toString()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                store.intervalHours = (value as String).toInt()
                settingsChanged()
                true
            }
        })
        screen.addPreference(ListPreference(context).apply {
            key = "provider"
            title = getString(R.string.provider)
            dialogTitle = title
            isPersistent = false
            entries = arrayOf("Open-Meteo", "MET Norway")
            entryValues = arrayOf(WeatherClient.PROVIDER_OPEN_METEO, WeatherClient.PROVIDER_MET_NORWAY)
            value = store.provider
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                store.provider = value as String
                settingsChanged()
                true
            }
        })
        status = Preference(context).apply {
            title = getString(R.string.status)
            isPersistent = false
            isSelectable = false
        }
        screen.addPreference(status)
        screen.addPreference(Preference(context).apply {
            title = getString(R.string.privacy)
            summary = getString(R.string.privacy_summary)
            isPersistent = false
            isSelectable = false
        })
        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        updateVisibility()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun settingsChanged() {
        RefreshJobService.reschedule(requireContext())
        updateVisibility()
    }

    private fun updateVisibility() {
        val manual = store.source == WeatherStore.SOURCE_MANUAL
        location.isSelectable = manual
        location.summary = store.location?.name ?: getString(R.string.location_none)
        deviceUpdate.isVisible = !manual
        val weather = store.currentWeather(System.currentTimeMillis())
        status.summary = when {
            !store.enabled -> getString(R.string.status_off)
            weather == null -> getString(R.string.status_waiting)
            else -> getString(
                R.string.status_updated,
                store.displayTemperature(weather).toString(),
                DateUtils.getRelativeTimeSpanString(store.fetchedAt()),
            )
        }
    }

    private fun askForLocation() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.location_hint)
            setSingleLine()
            val padding = (resources.displayMetrics.density * 24).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.location_title)
            .setView(input)
            .setPositiveButton(R.string.location_search) { _, _ -> applyLocationText(input.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyLocationText(text: String) {
        if (text.isEmpty()) return
        // Coordinates are used as they are, without contacting anyone
        val match = COORDINATES.matchEntire(text)
        val latitude = match?.groupValues?.get(1)?.toDoubleOrNull()
        val longitude = match?.groupValues?.get(2)?.toDoubleOrNull()
        if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            setLocation(latitude, longitude, "%.2f, %.2f".format(latitude, longitude))
            return
        }
        executor.execute {
            val result = try {
                WeatherClient.searchPlaces(text)
            } catch (e: IOException) {
                null
            }
            activity?.runOnUiThread { showPlaces(result) }
        }
    }

    private fun showPlaces(places: List<WeatherClient.Place>?) {
        if (!isAdded) return
        when {
            places == null -> toast(R.string.location_failed)
            places.isEmpty() -> toast(R.string.location_no_results)
            else -> AlertDialog.Builder(requireContext())
                .setTitle(R.string.location_results)
                .setItems(places.map { it.name }.toTypedArray()) { _, index ->
                    setLocation(places[index].latitude, places[index].longitude, places[index].name)
                }
                .show()
        }
    }

    private fun setLocation(latitude: Double, longitude: Double, name: String) {
        store.setLocation(latitude, longitude, name)
        settingsChanged()
    }

    private fun confirmDeviceLocation() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.device_confirm_title)
            .setMessage(R.string.device_confirm)
            .setPositiveButton(R.string.use_device) { _, _ -> requestLocationPermission() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        stopLocating()
        super.onDestroyView()
    }

    private fun requestLocationPermission() {
        val permission = Manifest.permission.ACCESS_COARSE_LOCATION
        if (requireContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            useDeviceLocation()
        } else {
            requestPermissions(arrayOf(permission), REQUEST_LOCATION)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            useDeviceLocation()
        } else {
            toast(R.string.device_denied)
        }
    }

    private fun useDeviceLocation() {
        store.source = WeatherStore.SOURCE_DEVICE
        source.value = WeatherStore.SOURCE_DEVICE
        updateVisibility()
        updateDeviceLocation()
    }

    /** A single fix, only on request. The refresh job reuses the saved position. */
    private fun updateDeviceLocation() {
        val context = requireContext()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        // With coarse permission fused only asks the network provider, which GrapheneOS leaves off by default
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            toast(R.string.device_failed)
            return
        }
        stopLocating()
        toast(R.string.device_locating)
        val listener = LocationListener { fix ->
            stopLocating()
            setLocation(
                fix.latitude, fix.longitude,
                "%.2f, %.2f".format(WeatherStore.roundCoordinate(fix.latitude), WeatherStore.roundCoordinate(fix.longitude)),
            )
        }
        val timeout = Runnable {
            stopLocating()
            toast(R.string.device_failed)
        }
        stopLocating = {
            manager.removeUpdates(listener)
            handler.removeCallbacks(timeout)
            stopLocating = {}
        }
        manager.requestLocationUpdates(
            provider, LocationRequest.Builder(0).setMaxUpdates(1).build(), context.mainExecutor, listener,
        )
        handler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    private fun toast(message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val LOCATION_TIMEOUT_MS = 90_000L
        private const val REQUEST_LOCATION = 1
        private val COORDINATES = Regex("""(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)""")
    }
}
