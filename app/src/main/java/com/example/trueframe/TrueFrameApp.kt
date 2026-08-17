package com.example.trueframe

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TrueFrameApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
