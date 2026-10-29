package com.sai.sports.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptMode
import com.sai.sports.fieldtest.FieldTestScreen
import com.sai.sports.fieldtest.FieldTestType
import com.sai.sports.notify.NotificationLink
import com.sai.sports.ui.auth.AccountReadyScreen
import com.sai.sports.ui.auth.AuthMode
import com.sai.sports.ui.auth.LoginScreen
import com.sai.sports.ui.auth.RegistrationScreen
import com.sai.sports.ui.capture.CaptureScreen
import com.sai.sports.ui.engage.BadgesScreen
import com.sai.sports.ui.engage.LeaderboardScreen
import com.sai.sports.ui.engage.SettingsScreen
import com.sai.sports.ui.history.HistoryScreen
import com.sai.sports.ui.home.HomeScreen
import com.sai.sports.ui.identity.IdentityCheckScreen
import com.sai.sports.ui.instructions.InstructionsScreen
import com.sai.sports.ui.kit.AppBottomBar
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.BarItem
import com.sai.sports.ui.notifications.NotificationsScreen
import com.sai.sports.ui.onboarding.OnboardingScreen
import com.sai.sports.ui.onboarding.OnboardingStore
import com.sai.sports.ui.onboarding.SplashScreen
import com.sai.sports.ui.onboarding.WelcomeScreen
import com.sai.sports.ui.practice.PracticeHistoryScreen
import com.sai.sports.ui.practice.PracticeScreen
import com.sai.sports.ui.profile.ProfileCompletionScreen
import com.sai.sports.ui.profile.ProfileScreen
import com.sai.sports.ui.profile.ServerResultScreen
import com.sai.sports.ui.results.ResultsScreen
import com.sai.sports.ui.sync.SyncStatusScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Where everything is, and the order an athlete meets it:
 *
 * ```
 * Splash ─▶ Language + introduction (first run) ─▶ Welcome
 *   Welcome ─▶ Create account: phone + code ─▶ 4 steps ─▶ photo ─▶ "You're all set"
 *   Welcome ─▶ Sign in: phone + code
 * ─▶ The app: Home · Progress · [Tests] · Ranks · Profile
 * ```
 *
 * A signed-in athlete goes from the splash straight to Home. The five tabs
 * share the bottom bar; everything else (a test, a result, settings) opens
 * over it, full screen, with its own way back.
 */
@Composable
fun AppNavigation(
    /** A tapped notification to open, once the athlete is signed in. */
    link: NotificationLink? = null,
    onLinkHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val navController = rememberNavController()

    LaunchedEffect(link) {
        val target = link ?: return@LaunchedEffect
        onLinkHandled()
        val session = AppServices.session(context)
        // Signed out, or someone else signed in since it was posted: the
        // notification was not for whoever holds the phone now.
        if (!session.isLoggedIn || !session.isRegistered) return@LaunchedEffect
        target.notificationId?.let { id ->
            launch(Dispatchers.IO) {
                runCatching { AppServices.api(context).markNotificationsRead(listOf(id)) }
            }
        }
        when {
            target.resultId != null -> navController.navigate("server-result/${target.resultId}")
            target.openHome -> navController.goToTab(ROUTE_HOME)
            else -> navController.navigate(ROUTE_NOTIFICATIONS)
        }
    }

    /** Where the athlete belongs now: decided at launch and after each account step. */
    fun entryRoute(): String {
        val session = AppServices.session(context)
        return when {
            !OnboardingStore.isComplete(context) -> ROUTE_ONBOARDING
            !session.isLoggedIn -> ROUTE_WELCOME
            !session.isRegistered -> ROUTE_REGISTER
            else -> ROUTE_HOME
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTab = currentRoute in TAB_ROUTES

    val tabs = listOf(
        BarItem(ROUTE_HOME, stringResource(R.string.nav_home), AppIcons.Home),
        BarItem(ROUTE_PROGRESS, stringResource(R.string.nav_progress), AppIcons.Chart),
        BarItem(ROUTE_RANKS, stringResource(R.string.nav_ranks), AppIcons.Trophy),
        BarItem(ROUTE_PROFILE, stringResource(R.string.nav_profile), AppIcons.User)
    )
    val centre = BarItem(ROUTE_TESTS, stringResource(R.string.nav_tests), AppIcons.Dumbbell)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (onTab) {
                AppBottomBar(
                    items = tabs,
                    centre = centre,
                    selected = currentRoute.orEmpty(),
                    onSelect = { route -> navController.goToTab(route) }
                )
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_SPLASH,
            // Over the tabs, the bar already keeps clear of the gesture bar,
            // so the screens must not add that inset a second time.
            modifier = if (onTab) {
                Modifier
                    .padding(inner)
                    .consumeWindowInsets(WindowInsets.navigationBars)
            } else {
                Modifier
            }
        ) {

            // -- getting in ---------------------------------------------------

            composable(ROUTE_SPLASH) {
                SplashScreen(onDone = { navController.replaceStackWith(entryRoute()) })
            }

            composable(ROUTE_ONBOARDING) {
                OnboardingScreen(onFinished = { navController.replaceStackWith(entryRoute()) })
            }

            composable(ROUTE_WELCOME) {
                WelcomeScreen(
                    onCreateAccount = { navController.navigate(authRoute(AuthMode.CREATE)) },
                    onSignIn = { navController.navigate(authRoute(AuthMode.SIGN_IN)) }
                )
            }

            composable(
                route = "auth/{mode}",
                arguments = listOf(navArgument("mode") { type = NavType.StringType })
            ) { entry ->
                val mode = runCatching { AuthMode.valueOf(entry.arguments?.getString("mode").orEmpty()) }
                    .getOrDefault(AuthMode.SIGN_IN)
                LoginScreen(
                    mode = mode,
                    onBack = { navController.popBackStack() },
                    onLoggedIn = { registered ->
                        navController.replaceStackWith(if (registered) ROUTE_HOME else ROUTE_REGISTER)
                    }
                )
            }

            composable(ROUTE_REGISTER) {
                RegistrationScreen(onRegistered = { navController.replaceStackWith(ROUTE_READY) })
            }

            composable(ROUTE_READY) {
                AccountReadyScreen(onContinue = { navController.replaceStackWith(ROUTE_HOME) })
            }

            // -- the five tabs -------------------------------------------------

            composable(ROUTE_HOME) {
                HomeScreen(
                    onSessionTest = { sessionId, code -> navController.startSessionTest(sessionId, code) },
                    onPractice = { navController.goToTab(ROUTE_TESTS) },
                    onPracticeTest = { type -> navController.navigate(instructionsRoute(type, AttemptMode.PRACTICE)) },
                    onPracticeHistory = { navController.navigate(ROUTE_PRACTICE_HISTORY) },
                    onSyncStatus = { navController.navigate(ROUTE_SYNC) },
                    onProfile = { navController.goToTab(ROUTE_PROFILE) },
                    onLeaderboard = { navController.goToTab(ROUTE_RANKS) },
                    onSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onCompleteProfile = { navController.navigate(ROUTE_COMPLETE_PROFILE) },
                    onNotifications = { navController.navigate(ROUTE_NOTIFICATIONS) }
                )
            }

            composable(ROUTE_TESTS) {
                PracticeScreen(
                    onTestSelected = { type -> navController.navigate(instructionsRoute(type, AttemptMode.PRACTICE)) },
                    onFieldTestSelected = { field -> navController.navigate(fieldRoute(field, AttemptMode.PRACTICE, null)) },
                    onHistory = { navController.navigate(ROUTE_PRACTICE_HISTORY) }
                )
            }

            composable(ROUTE_PROGRESS) {
                HistoryScreen(
                    onOfficialSelected = { resultId -> navController.navigate("server-result/$resultId") },
                    onPracticeSelected = { attemptId -> navController.navigate("results/$attemptId?review=true") }
                )
            }

            composable(ROUTE_RANKS) {
                LeaderboardScreen(onSettings = { navController.navigate(ROUTE_SETTINGS) })
            }

            composable(ROUTE_PROFILE) {
                ProfileScreen(
                    onResultSelected = { resultId -> navController.navigate("server-result/$resultId") },
                    onHistory = { navController.goToTab(ROUTE_PROGRESS) },
                    onBadges = { navController.navigate(ROUTE_BADGES) },
                    onLeaderboard = { navController.goToTab(ROUTE_RANKS) },
                    onSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onCompleteProfile = { navController.navigate(ROUTE_COMPLETE_PROFILE) }
                )
            }

            // -- over the tabs -------------------------------------------------

            composable(ROUTE_NOTIFICATIONS) {
                NotificationsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenResult = { resultId -> navController.navigate("server-result/$resultId") },
                    onOpenHome = { navController.goToTab(ROUTE_HOME) }
                )
            }

            composable(ROUTE_COMPLETE_PROFILE) {
                ProfileCompletionScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() }
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
                val code = backStackEntry.arguments?.getString("testType")
                val field = FieldTestType.fromCode(code)
                val testType = testTypeFrom(code)
                val sessionId = backStackEntry.arguments?.getString("session")
                if (sessionId == null) {
                    navController.popBackStack()
                    return@composable
                }
                IdentityCheckScreen(
                    fieldTest = field != null,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onVerified = {
                        // Replaced by the camera, so back from the test returns to
                        // the instructions rather than to a check already passed.
                        val next = if (field != null) {
                            fieldRoute(field, AttemptMode.OFFICIAL, sessionId)
                        } else {
                            attemptRoute("capture", testType, AttemptMode.OFFICIAL, sessionId)
                        }
                        navController.navigate(next) {
                            popUpTo(ROUTE_IDENTITY) { inclusive = true }
                        }
                    },
                    onCompleteProfile = { navController.navigate(ROUTE_COMPLETE_PROFILE) }
                )
            }

            composable(route = "field/{testType}/{mode}?session={session}", arguments = attemptArguments) { backStackEntry ->
                val field = FieldTestType.fromCode(backStackEntry.arguments?.getString("testType"))
                if (field == null) {
                    navController.popBackStack()
                    return@composable
                }
                FieldTestScreen(
                    testType = field,
                    mode = AttemptMode.fromName(backStackEntry.arguments?.getString("mode")),
                    sessionId = backStackEntry.arguments?.getString("session"),
                    onBack = { navController.popBackStack() },
                    onServerResult = { resultId -> navController.navigate("server-result/$resultId") }
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
                    // Practice returns to the tests, where the next attempt is
                    // one tap away; an official test returns home.
                    onDone = {
                        if (!navController.popBackStack(route = ROUTE_TESTS, inclusive = false)) {
                            navController.goToTab(ROUTE_HOME)
                        }
                    },
                    onServerResult = { resultId -> navController.navigate("server-result/$resultId") }
                )
            }

            composable(ROUTE_SYNC) {
                SyncStatusScreen(
                    onBack = { navController.popBackStack() },
                    onResultSelected = { resultId -> navController.navigate("server-result/$resultId") }
                )
            }

            composable(ROUTE_BADGES) {
                BadgesScreen(onBack = { navController.popBackStack() })
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = { navController.replaceStackWith(ROUTE_WELCOME) }
                )
            }

            composable(
                route = "server-result/{resultId}",
                arguments = listOf(navArgument("resultId") { type = NavType.StringType })
            ) { backStackEntry ->
                ServerResultScreen(
                    resultId = backStackEntry.arguments?.getString("resultId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenResult = { resultId -> navController.navigate("server-result/$resultId") }
                )
            }
        }
    }
}

/** An official test from a session: field runs go to their own setup, after the photo check. */
private fun NavHostController.startSessionTest(sessionId: String, code: String) {
    val field = FieldTestType.fromCode(code)
    if (field != null) {
        navigate("identity/${field.name}/${AttemptMode.OFFICIAL.name}?session=$sessionId")
    } else {
        navigate(instructionsRoute(testTypeFrom(code), AttemptMode.OFFICIAL, sessionId))
    }
}

/**
 * Switch tab the way every Android app does: one copy of each tab on the
 * stack, Home always underneath, and each tab's scroll position kept.
 */
private fun NavHostController.goToTab(route: String) {
    navigate(route) {
        popUpTo(ROUTE_HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun authRoute(mode: AuthMode) = "auth/${mode.name}"

private fun fieldRoute(testType: FieldTestType, mode: AttemptMode, sessionId: String?) =
    "field/${testType.name}/${mode.name}" + (sessionId?.let { "?session=$it" } ?: "")

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
 * the sign-in form, and back from the welcome screen after signing out must
 * not return to someone's profile.
 */
private fun NavHostController.replaceStackWith(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

private const val ROUTE_SPLASH = "splash"
private const val ROUTE_ONBOARDING = "onboarding"
private const val ROUTE_WELCOME = "welcome"
private const val ROUTE_REGISTER = "register"
private const val ROUTE_READY = "ready"
private const val ROUTE_HOME = "home"
private const val ROUTE_PROGRESS = "progress"
private const val ROUTE_TESTS = "tests"
private const val ROUTE_RANKS = "ranks"
private const val ROUTE_PROFILE = "profile"
private const val ROUTE_SYNC = "sync"
private const val ROUTE_BADGES = "badges"
private const val ROUTE_NOTIFICATIONS = "notifications"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_PRACTICE_HISTORY = "practice-history"
private const val ROUTE_COMPLETE_PROFILE = "complete-profile"
private const val ROUTE_IDENTITY = "identity/{testType}/{mode}?session={session}"

private val TAB_ROUTES = setOf(ROUTE_HOME, ROUTE_PROGRESS, ROUTE_TESTS, ROUTE_RANKS, ROUTE_PROFILE)
