package com.assistant.nova

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.assistant.nova.core.NovaPrefs
import com.assistant.nova.core.device.DeviceProfile
import com.assistant.nova.services.NovaListenerService
import com.assistant.nova.ui.screens.CapabilitiesScreen
import com.assistant.nova.ui.screens.DeviceCheckScreen
import com.assistant.nova.ui.screens.HomeScreen
import com.assistant.nova.ui.screens.RoutinesScreen
import com.assistant.nova.ui.screens.VoiceIdScreen
import com.assistant.nova.ui.screens.SettingsScreen
import com.assistant.nova.ui.theme.NovaAITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the "Tap to start listening" notification. */
        const val EXTRA_START_LISTENING = "start_listening"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            NovaAITheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    // Gentle slide + fade between screens
                    NavHost(
                        navController = nav,
                        startDestination = "home",
                        enterTransition = { slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(260)) },
                        exitTransition = { fadeOut(tween(200)) },
                        popEnterTransition = { fadeIn(tween(260)) },
                        popExitTransition = { slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(220)) },
                    ) {
                        composable("home") {
                            HomeScreen(
                                onDeviceCheck = { nav.navigate("device") },
                                onSettings = { nav.navigate("settings") },
                                onCapabilities = { nav.navigate("capabilities") },
                                onRoutines = { nav.navigate("routines") },
                            )
                        }
                        composable("routines") { RoutinesScreen(onBack = { nav.popBackStack() }) }
                        composable("capabilities") {
                            CapabilitiesScreen(
                                onBack = { nav.popBackStack() },
                                onDeviceCheck = { nav.navigate("device") },
                            )
                        }
                        composable("device") { DeviceCheckScreen(onBack = { nav.popBackStack() }) }
                        composable("settings") {
                            SettingsScreen(onBack = { nav.popBackStack() }, onVoiceId = { nav.navigate("voiceid") })
                        }
                        composable("voiceid") { VoiceIdScreen(onBack = { nav.popBackStack() }) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Android won't start a camera service by itself after a reboot: restart the privacy guard here
        val prefs = NovaPrefs(this)
        if (prefs.privacyGuard && !com.assistant.nova.services.PrivacyGuardService.isRunning &&
            DeviceProfile.granted(this, Manifest.permission.CAMERA)
        ) {
            com.assistant.nova.services.PrivacyGuardService.start(this)
        }
        // Nova is on screen, so Android allows starting the microphone now: resume 24/7 listening
        // if it was stopped (reboot, app update, killed by the system).
        if (NovaPrefs(this).alwaysOn && !NovaListenerService.isRunning &&
            DeviceProfile.granted(this, Manifest.permission.RECORD_AUDIO)
        ) {
            NovaListenerService.start(this)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_LISTENING, false) != true) return
        intent.removeExtra(EXTRA_START_LISTENING)
        NovaPrefs(this).alwaysOn = true
        if (DeviceProfile.granted(this, Manifest.permission.RECORD_AUDIO)) {
            NovaListenerService.start(this)
            // Came from the "tap to start" notification: get out of the way once listening has started
            lifecycleScope.launch {
                delay(1_500)
                moveTaskToBack(true)
            }
        }
    }
}
