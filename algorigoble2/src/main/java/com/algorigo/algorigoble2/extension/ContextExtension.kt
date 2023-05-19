package com.algorigo.algorigoble2.extension

import android.content.Context
import android.location.LocationManager
import androidx.core.content.ContextCompat

val Context.locationManager: LocationManager
    get() = ContextCompat.getSystemService(this, LocationManager::class.java)
        ?: error("LocationManager system service is not available")
