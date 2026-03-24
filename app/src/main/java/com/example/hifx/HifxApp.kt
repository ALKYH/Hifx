package com.example.hifx

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.hifx.audio.AudioEngine
import com.google.android.material.color.DynamicColors

class HifxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        AudioEngine.initialize(this)
        AppCompatDelegate.setDefaultNightMode(AudioEngine.getThemeMode())
    }
}
