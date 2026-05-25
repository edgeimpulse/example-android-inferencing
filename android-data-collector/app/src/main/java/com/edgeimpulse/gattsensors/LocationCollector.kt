package com.edgeimpulse.gattsensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 1 Hz GPS sampler. Mirrors [SensorCollector] so the Collect tab can record
 * a location track and upload it to Edge Impulse as a 4-axis sample stream
 * (lat, lon, alt, speed). Multi-modal capture (startAll path) also feeds
 * the unified per-sensor buffer via [DataRepository.appendPhoneSample].
 */
class LocationCollector(
    private val context: Context,
    private val repository: DataRepository,
) : LocationListener {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _dataFlow = MutableSharedFlow<SensorData>()
    val dataFlow = _dataFlow.asSharedFlow()

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var running = false

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!hasPermission()) {
            Log.w("LocationCollector", "ACCESS_FINE_LOCATION not granted; GPS capture skipped")
            return
        }
        running = true
        // Run callbacks on the main looper so Compose can safely observe
        // dataFlow without an extra dispatcher hop.
        val looper = Looper.getMainLooper()
        // Try GPS first, fall back to network. Either provider may be off,
        // so register both — we'll just take whichever fixes arrive.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { p ->
            if (locationManager.isProviderEnabled(p)) {
                try {
                    locationManager.requestLocationUpdates(p, 1_000L, 0f, this, looper)
                } catch (e: SecurityException) {
                    Log.e("LocationCollector", "Failed to register $p", e)
                }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { locationManager.removeUpdates(this) } catch (_: SecurityException) { }
    }

    override fun onLocationChanged(location: Location) {
        val lat   = location.latitude.toFloat()
        val lon   = location.longitude.toFloat()
        val alt   = location.altitude.toFloat()
        val speed = if (location.hasSpeed()) location.speed else 0f

        repository.appendPhoneSample("gps", floatArrayOf(lat, lon, alt, speed))

        val sample = SensorData(
            System.currentTimeMillis(),
            mapOf("lat" to lat, "lon" to lon, "alt" to alt, "speed" to speed),
        )
        ioScope.launch {
            _dataFlow.emit(sample)
            repository.saveSensorData(sample)
        }
    }

    // Pre-API-29 LocationListener overrides — needed for older OEM impls.
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) { }
    @Deprecated("Required by older Android versions")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
}
