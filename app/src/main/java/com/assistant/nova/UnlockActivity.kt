package com.assistant.nova

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Invisible screen used for voice unlock: it turns the display on, shows over the lock
 * screen and asks Android to bring up the PIN pad. Nova's accessibility service then
 * types the spoken PIN. Closes itself when the phone unlocks, the attempt is cancelled,
 * or after 20 seconds.
 */
class UnlockActivity : Activity() {

    companion object {
        @Volatile private var current: WeakReference<UnlockActivity>? = null

        fun finishIfOpen() {
            current?.get()?.let { a -> a.runOnUiThread { if (!a.isFinishing) a.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 20_000)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}
