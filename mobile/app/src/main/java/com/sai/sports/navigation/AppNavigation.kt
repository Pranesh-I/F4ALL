package com.sai.sports.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sai.sports.ui.capture.CaptureScreen
import com.sai.sports.ui.home.HomeScreen

@Composable
fun AppNavigation() {

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {

        composable("home") {

            HomeScreen(
                onTestSelected = { testName ->

                    navController.navigate(
                        "capture/${testName.replace(" ", "_")}"
                    )
                }
            )
        }

        composable(
            route = "capture/{testName}",
            arguments = listOf(
                navArgument("testName") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->

            val testName = backStackEntry
                .arguments
                ?.getString("testName")
                ?.replace("_", " ")
                ?: "Test"

            CaptureScreen(
                testName = testName,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}