package com.assistant.nova

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.services.NovaListenerService

/**
 * Invisible screen behind the "Talk to Nova" widget and Quick Settings tile. Opening a
 * screen (even for a moment) is what lets Android give Nova the microphone right away.
 */
class TalkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DeviceProfile.granted(this, Manifest.permission.RECORD_AUDIO)) {
            NovaListenerService.start(this, NovaListenerService.ACTION_TALK)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
