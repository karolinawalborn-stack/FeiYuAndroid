package com.feiyu.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.feiyu.companion.ui.AppRoot
import com.feiyu.companion.ui.FeiYuTheme

class MainActivity : ComponentActivity() {
    private val model: CompanionModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeiYuTheme(model.settings.theme) { AppRoot(model) }
        }
    }

    override fun onResume() {
        super.onResume()
        model.greetIfNeeded()
    }

    override fun onStop() {
        // Leaving the app ends a voice conversation (no listening in the background).
        model.stopVoiceMode()
        super.onStop()
    }
}
