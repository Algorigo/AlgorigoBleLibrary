package com.algorigo.algorigoble2

import android.content.Context
import androidx.startup.Initializer
import com.algorigo.algorigoble2.logging.DefaultLogger
import com.algorigo.algorigoble2.logging.Logging

object AlgorigoBleLibrary

internal lateinit var applicationContext: Context
    private set

internal lateinit var logging: Logging

class AlgorigoBleInitializer: Initializer<AlgorigoBleLibrary> {

    override fun create(context: Context): AlgorigoBleLibrary {
        applicationContext = context.applicationContext
        logging = Logging(DefaultLogger())
        return AlgorigoBleLibrary
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf()
}
