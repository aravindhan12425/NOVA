package com.assistant.nova.core.action

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/** Incoming calls: who is calling, answer, reject, and "reject and text" — all offline. */
class CallAssistant(private val context: Context) {

    private val telecom = context.getSystemService(TelecomManager::class.java)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Android needs these to tell Nova a call is ringing, who it is, and to answer/reject it. */
    val permissions = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    fun canAnswer() = granted(Manifest.permission.ANSWER_PHONE_CALLS)

    /** A call is connected (or dialling / on hold). */
    @SuppressLint("MissingPermission")
    fun inCall(): Boolean {
        if (granted(Manifest.permission.READ_PHONE_STATE)) {
            try { if (telecom.isInCall) return true } catch (_: Exception) {}
        }
        val mode = context.getSystemService(android.media.AudioManager::class.java).mode
        return mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    }

    /** Brings the phone app's call screen to the front (needed to press its buttons). */
    @SuppressLint("MissingPermission")
    fun showCallScreen() {
        try { if (granted(Manifest.permission.READ_PHONE_STATE)) telecom.showInCallScreen(false) } catch (_: Exception) {}
    }

    /** Contact name for [number], the number read digit by digit, or "Unknown number". */
    fun callerName(number: String?): String {
        if (number.isNullOrBlank()) return "Unknown number"
        if (granted(Manifest.permission.READ_CONTACTS)) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0)
                }
            } catch (_: Exception) {
            }
        }
        return number.filter { it.isDigit() }.takeLast(10).toList().joinToString(" ")
    }

    @SuppressLint("MissingPermission")
    fun answer(): Boolean = try {
        if (canAnswer()) { telecom.acceptRingingCall(); true } else false
    } catch (_: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun reject(): Boolean = try {
        if (canAnswer() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) telecom.endCall() else false
    } catch (_: Exception) {
        false
    }

    /**
     * Sends an SMS. On dual-SIM phones set to "ask every time" there is no default SMS SIM,
     * so Nova uses the first active SIM instead of failing silently.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun text(number: String?, message: String): Boolean {
        if (number.isNullOrBlank() || !granted(Manifest.permission.SEND_SMS)) return false
        return try {
            var subId = SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID && granted(Manifest.permission.READ_PHONE_STATE)) {
                subId = context.getSystemService(SubscriptionManager::class.java)
                    ?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId ?: subId
            }
            val sms = when {
                subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
                else -> SmsManager.getSmsManagerForSubscriptionId(subId)
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null)
            android.util.Log.i("NovaCall", "SMS sent (${message.length} chars) on SIM sub $subId")
            true
        } catch (e: Exception) {
            android.util.Log.w("NovaCall", "SMS failed", e)
            false
        }
    }

    /** Number of the most recent call (incoming or outgoing), from the call log. */
    fun lastCallNumber(): String? {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
