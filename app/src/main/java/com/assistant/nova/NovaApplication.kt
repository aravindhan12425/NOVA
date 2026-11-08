package com.assistant.nova

import android.app.Application
import com.assistant.nova.services.Notifications

class NovaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
