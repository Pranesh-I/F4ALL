package com.sai.sports.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.api.ApiResult
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptStore
import com.sai.sports.data.PracticeRecord
import com.sai.sports.data.PracticeStats
import com.sai.sports.data.ProfileStatusStore
import com.sai.sports.data.SyncRepository
import com.sai.sports.fieldtest.FieldAttemptStore
import com.sai.sports.notify.NotificationWorker
import com.sai.sports.sync.NetworkMonitor
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.TestArt
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.Avatar
import com.sai.sports.ui.kit.Eyebrow
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.ProgressRing
import com.sai.sports.ui.kit.RoundIconButton
import com.sai.sports.ui.kit.SectionHeader
import com.sai.sports.ui.kit.Tag
import com.sai.sports.ui.kit.TagTone
import com.sai.sports.ui.kit.OfflineBar
import com.sai.sports.ui.practice.SyncPracticeOnOpen
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The athlete's home: their chest number, the official trials open to them,
 * their practice, and how to reach everything else.
 *
 * The two worlds look different on purpose. Official tests sit on the black
 * floodlit band with the marigold Start; practice is rough work on a dashed
 * pencil sheet. An athlete who only wants to train must never submit an
 * official attempt by accident.
 */
@Composable
fun HomeScreen(
    onSessionTest: (sessionId: String, code: String) -> Unit,
    onPractice: () -> Unit,
    onPracticeTest: (TestType) -> Unit,
    onPracticeHistory: () -> Unit,
    onSyncStatus: () -> Unit,
    onProfile: () -> Unit,
    onLeaderboard: () -> Unit,
    onSettings: () -> Unit,
    onCompleteProfile: () -> Unit,
    onNotifications: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { SyncRepository(context) }
    val statusStore = remember { ProfileStatusStore(context) }
    val attempts = remember { AttemptStore(context) }

    // This athlete's waiting tests, not everyone who has used the phone.
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val queuedCount by remember(athleteId) {
        athleteId?.let(repository::observePendingCount) ?: flowOf(0)
    }.collectAsStateWithLifecycle(initialValue = 0)
    // Official field runs waiting for a signal count too: they have no video
    // in the upload queue, but they have not reached SAI either.
    val fieldWaiting by produceState(initialValue = 0, athleteId) {
        value = withContext(Dispatchers.IO) {
            athleteId?.let { FieldAttemptStore(context).list(it).count { run -> run.waitingToSend } } ?: 0
        }
    }
    val pendingCount = queuedCount + fieldWaiting
    val online by remember { NetworkMonitor.observeOnline(context) }
        .collectAsStateWithLifecycle(initialValue = true)

    // What this phone last knew about the profile, shown at once, then
    // refreshed from the server: who the chest number belongs to, and what
    // still stands between the athlete and an official test.
    var card by remember(athleteId) { mutableStateOf(athleteId?.let(statusStore::card)) }
    var missing by remember(athleteId) { mutableStateOf(athleteId?.let(statusStore::missing).orEmpty()) }

    LaunchedEffect(athleteId) {
        if (athleteId == null) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { AppServices.api(context).profile() }
        if (result is ApiResult.Success) {
            statusStore.save(result.value)
            missing = result.value.missing
            card = statusStore.card(athleteId)
        }
    }

    // News from SAI: how many the athlete has not read yet, from their account.
    var unread by remember(athleteId) { mutableIntStateOf(0) }
    LaunchedEffect(athleteId) {
        if (athleteId == null) return@LaunchedEffect
        val page = withContext(Dispatchers.IO) {
            AppServices.api(context).notifications(unreadOnly = true, limit = 1)
        }
        if (page is ApiResult.Success) unread = page.value.unreadCount
    }
    AskForNotificationsOnce()

    // Recomputed on every visit, so a best set a moment ago is already here.
    var practiceRefreshes by remember { mutableIntStateOf(0) }
    val practice by produceState<List<PracticeRecord>?>(initialValue = null, practiceRefreshes) {
        value = withContext(Dispatchers.IO) {
            PracticeStats.records(athleteId?.let(attempts::listAttempts).orEmpty())
        }
    }
    SyncPracticeOnOpen(athleteId, attempts) { practiceRefreshes++ }

    Page(
        header = {
            HomeHeader(
                name = card?.name,
                unread = unread,
                onProfile = onProfile,
                onNotifications = onNotifications,
                onSettings = onSettings
            )
        },
        footer = {
            /*
             * The pending count is on the home screen, not buried in a menu.
             * An athlete in a low-connectivity area needs to know at a glance
             * that a test has not reached SAI yet — otherwise they assume it
             * has, and find out otherwise far too late.
             */
            if (pendingCount > 0 && !online) {
                OfflineBar(stringResource(R.string.home_sync_offline))
            }
        },
        spacing = 18.dp
    ) {
        // Official tests need a complete profile; say so before the session does.
        ProfileCompletionBanner(missing = missing, onCompleteProfile = onCompleteProfile)

        // Official tests only exist inside a session SAI has opened.
        SessionsSection(athleteId = athleteId, onSessionTest = onSessionTest)

        SectionHeader(stringResource(R.string.home_activity_title))
        ActivityTiles(
            records = practice,
            pendingCount = pendingCount,
            onPractice = onPractice,
            onSyncStatus = onSyncStatus
        )

        SectionHeader(
            title = stringResource(R.string.home_practice),
            action = stringResource(R.string.home_view_all),
            onAction = onPractice
        )
        PracticeCards(
            records = practice,
            onPracticeTest = onPracticeTest,
            onPracticeHistory = onPracticeHistory
        )

        Text(
            stringResource(R.string.home_provisional_note),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
        )
    }
}

/**
 * Android 13 and later need permission to show notifications. Asked once, on
 * the home screen after signing in, where the athlete can see why it matters;
 * a refusal is respected, and news still waits in the in-app inbox.
 */
@Composable
private fun AskForNotificationsOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) NotificationWorker.checkSoon(context)
    }
    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences(PERMISSION_PREFERENCES, Context.MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !preferences.getBoolean(ASKED, false)) {
            preferences.edit { putBoolean(ASKED, true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private const val PERMISSION_PREFERENCES = "permissions"
private const val ASKED = "asked_notifications"

/**
 * Who is signed in and what day it is, with the bell and settings to hand.
 * The initials open the profile.
 */
@Composable
private fun HomeHeader(
    name: String?,
    unread: Int,
    onProfile: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit
) {
    val today = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
    }
    val firstName = name?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(
            name = name,
            highlight = true,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.nav_profile),
                    onClick = onProfile
                )
        )
        Column(Modifier.weight(1f)) {
            Eyebrow(today)
            Text(
                text = firstName?.let { stringResource(R.string.home_greeting, it) }
                    ?: stringResource(R.string.home_greeting_anon),
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
        }
        RoundIconButton(
            icon = AppIcons.Bell,
            contentDescription = if (unread > 0) {
                stringResource(R.string.home_notifications_unread, unread)
            } else {
                stringResource(R.string.notifications_title)
            },
            onClick = onNotifications,
            badge = unread > 0
        )
        RoundIconButton(
            icon = AppIcons.Gear,
            contentDescription = stringResource(R.string.nav_settings),
            onClick = onSettings
        )
    }
}

/** Two tiles side by side: how much practice, and whether everything reached SAI. */
@Composable
private fun ActivityTiles(
    records: List<PracticeRecord>?,
    pendingCount: Int,
    onPractice: () -> Unit,
    onSyncStatus: () -> Unit
) {
    val total = records?.size ?: 0
    val tried = records?.count { it.attempts > 0 } ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        ActivityTile(
            icon = AppIcons.Dumbbell,
            title = stringResource(R.string.home_tests_tried),
            detail = stringResource(R.string.home_tests_tried_value, tried, total),
            onClick = onPractice,
            modifier = Modifier.weight(1f)
        ) {
            ProgressRing(
                fraction = if (total == 0) 0f else tried.toFloat() / total,
                label = "$tried/$total",
                size = 58.dp
            )
        }
        ActivityTile(
            icon = AppIcons.Upload,
            title = stringResource(R.string.home_uploads_title),
            detail = if (pendingCount > 0) {
                stringResource(R.string.home_sync_waiting, pendingCount)
            } else {
                stringResource(R.string.home_uploads_all_sent)
            },
            detailColor = if (pendingCount > 0) AppColors.Warning else AppColors.Success,
            onClick = onSyncStatus,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .background(
                        if (pendingCount > 0) AppColors.SurfaceHigh else AppColors.SuccessSoft,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (pendingCount > 0) AppIcons.Clock else AppIcons.Check,
                    contentDescription = null,
                    tint = if (pendingCount > 0) AppColors.Warning else AppColors.Success,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun ActivityTile(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detailColor: Color = AppColors.TextSecondary,
    figure: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(AppColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            figure()
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
        }
    }
}

/**
 * Practice, one card per test: its best so far and its picture, one tap from
 * starting. Showing the best turns practice into something to beat.
 */
@Composable
private fun PracticeCards(
    records: List<PracticeRecord>?,
    onPracticeTest: (TestType) -> Unit,
    onPracticeHistory: () -> Unit
) {
    if (records == null) {
        Text(
            stringResource(R.string.loading),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextSecondary
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        records.forEach { record ->
            val best = record.best
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppColors.Surface)
                    .clickable(role = Role.Button, onClick = { onPracticeTest(record.testType) })
                    .padding(14.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(
                        text = stringResource(R.string.practice_badge),
                        tone = TagTone.Neutral,
                        icon = AppIcons.Lock
                    )
                    Text(
                        stringResource(Labels.testName(record.testType)),
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (best != null) AppIcons.Trophy else AppIcons.Flame,
                            contentDescription = null,
                            tint = if (best != null) AppColors.Accent else AppColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = when {
                                best != null -> stringResource(
                                    R.string.practice_best,
                                    best.result.formattedScore() + " " + stringResource(Labels.unit(best.result.unit))
                                )
                                record.attempts == 0 -> stringResource(R.string.practice_not_tried)
                                else -> stringResource(R.string.history_not_scored)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary
                        )
                    }
                }
                TestArt(code = record.testType.name)
            }
        }
        LineButton(
            text = stringResource(R.string.practice_history),
            onClick = onPracticeHistory,
            icon = AppIcons.List,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
