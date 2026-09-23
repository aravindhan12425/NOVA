package com.assistant.nova

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.assistant.nova.ui.screens.HomeScreen
import com.assistant.nova.ui.screens.SettingsScreen
import com.assistant.nova.ui.theme.NovaAITheme
import com.assistant.nova.utils.PreferencesManager

class MainActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            var wakeWord by remember { mutableStateOf(prefs.getWakeWord()) }
            val navController = rememberNavController()

            NovaAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                wakeWord = wakeWord,
                                onSettingsClick = { navController.navigate("settings") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                currentWakeWord = wakeWord,
                                onWakeWordSaved = { newWord ->
                                    prefs.setWakeWord(newWord)
                                    wakeWord = newWord
                                    navController.popBackStack()
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
