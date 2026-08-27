package com.sai.sports.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sai.sports.ui.capture.CaptureScreen
import com.sai.sports.ui.home.HomeScreen
import com.sai.sports.ui.results.ResultsScreen
import com.sai.sports.ui.sync.SyncStatusScreen

@Composable
fun AppNavigation() {

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME
    ) {

        composable(ROUTE_HOME) {

            HomeScreen(
                onTestSelected = { testName ->
                    navController.navigate(
                        "capture/${testName.replace(" ", "_")}"
                    )
                },
                onSyncStatus = {
                    navController.navigate(ROUTE_SYNC)
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
                },
                onAttemptComplete = { attemptId ->
                    // Capture stays on the stack so "Try again" on the results
                    // screen is just a pop back to a re-armed capture screen.
                    navController.navigate("results/$attemptId")
                }
            )
        }

        composable(
            route = "results/{attemptId}",
            arguments = listOf(
                navArgument("attemptId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->

            val attemptId = backStackEntry
                .arguments
                ?.getString("attemptId")
                ?: ""

            ResultsScreen(
                attemptId = attemptId,
                onRetry = {
                    navController.popBackStack()
                },
                onDone = {
                    navController.popBackStack(
                        route = ROUTE_HOME,
                        inclusive = false
                    )
                }
            )
        }

        composable(ROUTE_SYNC) {
            SyncStatusScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_SYNC = "sync"
