package com.sai.sports

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sai.sports.navigation.AppNavigation
import com.sai.sports.ui.theme.SAISportsTalentAssessmentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SAISportsTalentAssessmentTheme {
                AppNavigation()
            }
        }
    }
}