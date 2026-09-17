package com.sai.sports.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.ui.auth.LoginScreen
import com.sai.sports.ui.auth.RegistrationScreen
import com.sai.sports.ui.capture.CaptureScreen
import com.sai.sports.ui.engage.BadgesScreen
import com.sai.sports.ui.engage.LeaderboardScreen
import com.sai.sports.ui.engage.SettingsScreen
import com.sai.sports.ui.home.HomeScreen
import com.sai.sports.ui.instructions.InstructionsScreen
import com.sai.sports.ui.onboarding.OnboardingScreen
import com.sai.sports.ui.onboarding.OnboardingStore
import com.sai.sports.ui.profile.ProfileScreen
import com.sai.sports.ui.profile.ServerResultScreen
import com.sai.sports.ui.results.ResultsScreen
import com.sai.sports.ui.sync.SyncStatusScreen

@Composable
fun AppNavigation() {

    val context = LocalContext.current
    val navController = rememberNavController()

    // Decided once, at launch. Recording a test requires an account, because a
    // test recorded without one has nobody to be submitted for.
    val startDestination = remember {
        val session = AppServices.session(context)
        when {
            !OnboardingStore.isComplete(context) -> ROUTE_ONBOARDING
            !session.isLoggedIn -> ROUTE_LOGIN
            !session.isRegistered -> ROUTE_REGISTER
            else -> ROUTE_HOME
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {

        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    val session = AppServices.session(context)
                    navController.replaceStackWith(
                        when {
                            !session.isLoggedIn -> ROUTE_LOGIN
                            !session.isRegistered -> ROUTE_REGISTER
                            else -> ROUTE_HOME
                        }
                    )
                }
            )
        }

        composable(ROUTE_LOGIN) {
            LoginScreen(
                onLoggedIn = { registered ->
                    navController.replaceStackWith(
                        if (registered) ROUTE_HOME else ROUTE_REGISTER
                    )
                }
            )
        }

        composable(ROUTE_REGISTER) {
            RegistrationScreen(
                onRegistered = { navController.replaceStackWith(ROUTE_HOME) }
            )
        }

        composable(ROUTE_HOME) {
            HomeScreen(
                onTestSelected = { type -> navController.navigate("instructions/${type.name}") },
                onSyncStatus = { navController.navigate(ROUTE_SYNC) },
                onProfile = { navController.navigate(ROUTE_PROFILE) },
                onLeaderboard = { navController.navigate(ROUTE_LEADERBOARD) },
                onSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }

        composable(
            route = "instructions/{testType}",
            arguments = listOf(navArgument("testType") { type = NavType.StringType })
        ) { backStackEntry ->
            val testType = testTypeFrom(backStackEntry.arguments?.getString("testType"))
            InstructionsScreen(
                testType = testType,
                onBack = { navController.popBackStack() },
                onStart = { navController.navigate("capture/${testType.name}") }
            )
        }

        composable(
            route = "capture/{testType}",
            arguments = listOf(navArgument("testType") { type = NavType.StringType })
        ) { backStackEntry ->
            CaptureScreen(
                testType = testTypeFrom(backStackEntry.arguments?.getString("testType")),
                onBack = { navController.popBackStack() },
                onAttemptComplete = { attemptId ->
                    // Capture stays on the stack so "Try again" on the results
                    // screen is just a pop back to a re-armed capture screen.
                    navController.navigate("results/$attemptId")
                }
            )
        }

        composable(
            route = "results/{attemptId}",
            arguments = listOf(navArgument("attemptId") { type = NavType.StringType })
        ) { backStackEntry ->
            ResultsScreen(
                attemptId = backStackEntry.arguments?.getString("attemptId") ?: "",
                onRetry = { navController.popBackStack() },
                onDone = { navController.popBackStack(route = ROUTE_HOME, inclusive = false) }
            )
        }

        composable(ROUTE_SYNC) {
            SyncStatusScreen(
                onBack = { navController.popBackStack() },
                onResultSelected = { resultId -> navController.navigate("server-result/$resultId") }
            )
        }

        composable(ROUTE_PROFILE) {
            ProfileScreen(
                onBack = { navController.popBackStack() },
                onResultSelected = { resultId -> navController.navigate("server-result/$resultId") },
                onBadges = { navController.navigate(ROUTE_BADGES) },
                onLeaderboard = { navController.navigate(ROUTE_LEADERBOARD) },
                onSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }

        composable(ROUTE_BADGES) {
            BadgesScreen(onBack = { navController.popBackStack() })
        }

        composable(ROUTE_LEADERBOARD) {
            LeaderboardScreen(
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = { navController.replaceStackWith(ROUTE_LOGIN) }
            )
        }

        composable(
            route = "server-result/{resultId}",
            arguments = listOf(navArgument("resultId") { type = NavType.StringType })
        ) { backStackEntry ->
            ServerResultScreen(
                resultId = backStackEntry.arguments?.getString("resultId").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** Routes carry the stable enum name, never a display name that is now translated. */
private fun testTypeFrom(value: String?): TestType =
    runCatching { TestType.valueOf(value.orEmpty()) }.getOrDefault(TestType.SIT_UPS)

/**
 * Navigate to [route] with nothing behind it.
 *
 * Used at the account boundaries: back from the home screen must not return to
 * the login form, and back from the login form after signing out must not
 * return to someone's profile.
 */
private fun NavHostController.replaceStackWith(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_REGISTER = "register"
private const val ROUTE_HOME = "home"
private const val ROUTE_SYNC = "sync"
private const val ROUTE_PROFILE = "profile"
private const val ROUTE_BADGES = "badges"
private const val ROUTE_LEADERBOARD = "leaderboard"
private const val ROUTE_SETTINGS = "settings"
