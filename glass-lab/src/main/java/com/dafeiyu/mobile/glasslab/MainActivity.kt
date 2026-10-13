package com.dafeiyu.mobile.glasslab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val lab: LabState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = lab.document.selectedTheme == "dark"
            SideEffect {
                // The experiment's selected theme can differ from the Android system theme.
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LabApp(lab)
        }
    }

    override fun onStop() {
        // Synchronously submit the final immutable document before this owner can be cleared.
        // File IO is performed by the process-scoped serial writer, never on the UI thread.
        lab.persistNow()
        super.onStop()
    }
}
