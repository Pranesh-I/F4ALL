package com.sai.sports

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.navigation.AppNavigation
import com.sai.sports.ui.theme.TrialsColors
import com.sai.sports.ui.theme.TrialsTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Every string the athlete reads resolves through this context, so the
        // chosen language applies to the whole UI without touching the system
        // language of a phone that may be shared with the family.
        super.attachBaseContext(LanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 draws every app edge to edge whether it asks or not, so
        // the app asks on every version and pads each screen for the system
        // bars itself (see ui/kit/Page). Dark icons, for the light paper.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            TrialsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = TrialsColors.Ground) {
                    AppNavigation()
                }
            }
        }
    }
}
