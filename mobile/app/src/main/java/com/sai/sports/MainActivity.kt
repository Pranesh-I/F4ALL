package com.sai.sports

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sai.sports.i18n.LanguageStore
import com.sai.sports.navigation.AppNavigation
import com.sai.sports.ui.theme.SAISportsTalentAssessmentTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Every string the athlete reads resolves through this context, so the
        // chosen language applies to the whole UI without touching the system
        // language of a phone that may be shared with the family.
        super.attachBaseContext(LanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SAISportsTalentAssessmentTheme {
                AppNavigation()
            }
        }
    }
}
