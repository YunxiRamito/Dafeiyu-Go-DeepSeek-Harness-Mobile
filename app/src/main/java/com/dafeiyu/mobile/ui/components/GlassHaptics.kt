package com.dafeiyu.mobile.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** Platform confirmation honors system/user haptic settings and lets the device choose its actuator. */
class GlassHaptics internal constructor(private val view: View?) {
    fun confirm() { view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
}
val LocalGlassHaptics = staticCompositionLocalOf { GlassHaptics(null) }
@Composable
fun rememberGlassHaptics(): GlassHaptics {
    val view = LocalView.current
    return remember(view) { GlassHaptics(view) }
}
