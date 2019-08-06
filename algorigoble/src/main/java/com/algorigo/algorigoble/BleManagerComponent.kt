package com.algorigo.algorigoble

import android.content.Context
import com.algorigo.algorigoble.impl.BleDeviceEngineImpl
import com.algorigo.algorigoble.impl.BleManagerImpl
import com.algorigo.algorigoble.rxandroidble.BleDeviceEngineRxAndroidBle
import com.algorigo.algorigoble.rxandroidble.BleManagerRxAndroidBle
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Singleton
@Component(modules = arrayOf(BleManagerComponent.BleManagerModule::class))
interface BleManagerComponent {
    fun bleManager(): BleManager
    fun bleDeviceEngine(): BleDeviceEngine

    @Module
    class BleManagerModule(private val context: Context) {

        @Provides
        @Singleton
        fun providesBleManager(): BleManager{
            return BleManagerImpl().apply {
                initialize(context.applicationContext)
            }
//            return BleManagerRxAndroidBle().apply {
//                initialize(context.applicationContext)
//            }
        }

        @Provides
        fun providesBleDeviceEngine(): BleDeviceEngine {
            return BleDeviceEngineImpl()
//            return BleDeviceEngineRxAndroidBle()
        }
    }
}