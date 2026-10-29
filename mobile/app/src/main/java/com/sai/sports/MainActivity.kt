package com.sai.sports

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.navigation.AppNavigation
import com.sai.sports.notify.NotificationLink
import com.sai.sports.ui.theme.AppColors
import com.sai.sports.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    // A tapped notification, waiting for the navigation to act on it.
    private var link by mutableStateOf<NotificationLink?>(null)

    override fun attachBaseContext(newBase: Context) {
        // Every string the athlete reads resolves through this context, so the
        // chosen language applies to the whole UI without touching the system
        // language of a phone that may be shared with the family.
        super.attachBaseContext(LanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 draws every app edge to edge whether it asks or not, so
        // the app asks on every version and pads each screen for the system
        // bars itself (see ui/kit/Page). Light icons, for the dark screens.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) link = NotificationLink.from(intent)

        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
                    AppNavigation(link = link, onLinkHandled = { link = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        NotificationLink.from(intent)?.let { link = it }
    }
}
