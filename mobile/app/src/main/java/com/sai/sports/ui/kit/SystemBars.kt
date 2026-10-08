package com.sai.sports.ui.kit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * White status and navigation bar icons while this composable is on screen,
 * for screens drawn on something dark (the camera). Restores whatever was set
 * before when the screen goes, so the paper screens keep their dark icons.
 */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false

        onDispose {
            lightStatus?.let { controller.isAppearanceLightStatusBars = it }
            lightNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
