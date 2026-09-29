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
import com.sai.sports.data.AttemptMode
import com.sai.sports.ui.auth.LoginScreen
import com.sai.sports.ui.auth.RegistrationScreen
import com.sai.sports.ui.capture.CaptureScreen
import com.sai.sports.ui.engage.BadgesScreen
import com.sai.sports.ui.engage.LeaderboardScreen
import com.sai.sports.ui.engage.SettingsScreen
import com.sai.sports.ui.home.HomeScreen
import com.sai.sports.ui.identity.IdentityCheckScreen
import com.sai.sports.ui.instructions.InstructionsScreen
import com.sai.sports.ui.onboarding.OnboardingScreen
import com.sai.sports.ui.onboarding.OnboardingStore
import com.sai.sports.ui.practice.PracticeHistoryScreen
import com.sai.sports.ui.practice.PracticeScreen
import com.sai.sports.ui.profile.ProfileCompletionScreen
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
                onSessionTest = { sessionId, type ->
                    navController.navigate(instructionsRoute(type, AttemptMode.OFFICIAL, sessionId))
                },
                onPractice = { navController.navigate(ROUTE_PRACTICE) },
                onSyncStatus = { navController.navigate(ROUTE_SYNC) },
                onProfile = { navController.navigate(ROUTE_PROFILE) },
                onLeaderboard = { navController.navigate(ROUTE_LEADERBOARD) },
                onSettings = { navController.navigate(ROUTE_SETTINGS) },
                onCompleteProfile = { navController.navigate(ROUTE_COMPLETE_PROFILE) }
            )
        }

        composable(ROUTE_COMPLETE_PROFILE) {
            ProfileCompletionScreen(
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() }
            )
        }

        composable(ROUTE_PRACTICE) {
            PracticeScreen(
                onBack = { navController.popBackStack() },
                onTestSelected = { type -> navController.navigate(instructionsRoute(type, AttemptMode.PRACTICE)) },
                onHistory = { navController.navigate(ROUTE_PRACTICE_HISTORY) }
            )
        }

        composable(ROUTE_PRACTICE_HISTORY) {
            PracticeHistoryScreen(
                onBack = { navController.popBackStack() },
                onAttemptSelected = { attemptId -> navController.navigate("results/$attemptId?review=true") }
            )
        }

        composable(
            route = "instructions/{testType}/{mode}?session={session}",
            arguments = attemptArguments
        ) { backStackEntry ->
            val testType = testTypeFrom(backStackEntry.arguments?.getString("testType"))
            val mode = AttemptMode.fromName(backStackEntry.arguments?.getString("mode"))
            val sessionId = backStackEntry.arguments?.getString("session")
            InstructionsScreen(
                testType = testType,
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                onStart = {
                    // An official test starts with the photo check; practice
                    // goes straight to the camera.
                    val next = if (mode == AttemptMode.OFFICIAL && sessionId != null) "identity" else "capture"
                    navController.navigate(attemptRoute(next, testType, mode, sessionId))
                }
            )
        }

        composable(route = ROUTE_IDENTITY, arguments = attemptArguments) { backStackEntry ->
            val testType = testTypeFrom(backStackEntry.arguments?.getString("testType"))
            val sessionId = backStackEntry.arguments?.getString("session")
            if (sessionId == null) {
                navController.popBackStack()
                return@composable
            }
            IdentityCheckScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
                onVerified = {
                    // Replaced by the camera, so back from the test returns to
                    // the instructions rather than to a check already passed.
                    navController.navigate(attemptRoute("capture", testType, AttemptMode.OFFICIAL, sessionId)) {
                        popUpTo(ROUTE_IDENTITY) { inclusive = true }
                    }
                },
                onCompleteProfile = { navController.navigate(ROUTE_COMPLETE_PROFILE) }
            )
        }

        composable(
            route = "capture/{testType}/{mode}?session={session}",
            arguments = attemptArguments
        ) { backStackEntry ->
            CaptureScreen(
                testType = testTypeFrom(backStackEntry.arguments?.getString("testType")),
                mode = AttemptMode.fromName(backStackEntry.arguments?.getString("mode")),
                sessionId = backStackEntry.arguments?.getString("session"),
                onBack = { navController.popBackStack() },
                onAttemptComplete = { attemptId ->
                    // Capture stays on the stack so "Try again" on the results
                    // screen is just a pop back to a re-armed capture screen.
                    navController.navigate("results/$attemptId")
                }
            )
        }

        composable(
            route = "results/{attemptId}?review={review}",
            arguments = listOf(
                navArgument("attemptId") { type = NavType.StringType },
                navArgument("review") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            ResultsScreen(
                attemptId = backStackEntry.arguments?.getString("attemptId") ?: "",
                reviewing = backStackEntry.arguments?.getBoolean("review") ?: false,
                // Straight after recording, this pops back to a re-armed
                // capture screen; from history, back to the list.
                onRetry = { navController.popBackStack() },
                // Practice returns to practice, where the next attempt is one
                // tap away; an official test returns home.
                onDone = {
                    if (!navController.popBackStack(route = ROUTE_PRACTICE, inclusive = false)) {
                        navController.popBackStack(route = ROUTE_HOME, inclusive = false)
                    }
                }
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
                onSettings = { navController.navigate(ROUTE_SETTINGS) },
                onCompleteProfile = { navController.navigate(ROUTE_COMPLETE_PROFILE) }
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

private fun instructionsRoute(testType: TestType, mode: AttemptMode, sessionId: String? = null) =
    attemptRoute("instructions", testType, mode, sessionId)

/** An official attempt carries its assessment session all the way to the recording. */
private fun attemptRoute(screen: String, testType: TestType, mode: AttemptMode, sessionId: String?) =
    "$screen/${testType.name}/${mode.name}" + (sessionId?.let { "?session=$it" } ?: "")

private val attemptArguments = listOf(
    navArgument("testType") { type = NavType.StringType },
    navArgument("mode") { type = NavType.StringType },
    navArgument("session") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
)

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
private const val ROUTE_PRACTICE = "practice"
private const val ROUTE_PRACTICE_HISTORY = "practice-history"
private const val ROUTE_COMPLETE_PROFILE = "complete-profile"
private const val ROUTE_IDENTITY = "identity/{testType}/{mode}?session={session}"
