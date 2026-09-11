package com.example.trueframe

import android.app.Application
import com.example.trueframe.data.ProxyCacheManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TrueFrameApp : Application() {

    @Inject
    lateinit var proxyCacheManager: ProxyCacheManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        
        applicationScope.launch {
            proxyCacheManager.reconcileCache()
        }
    }
}
