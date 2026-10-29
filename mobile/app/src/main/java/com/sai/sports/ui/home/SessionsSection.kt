package com.sai.sports.ui.home

import androidx.compose.foundation.background
import com.sai.sports.ui.kit.TagTone
import com.sai.sports.ui.kit.Tag
import com.sai.sports.ui.kit.Eyebrow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.auth.AppServices
import com.sai.sports.data.CachedSessions
import com.sai.sports.data.SessionAvailability
import com.sai.sports.data.SessionRepository
import com.sai.sports.data.SessionTestState
import com.sai.sports.data.SessionTestView
import com.sai.sports.data.SessionView
import com.sai.sports.data.SyncRepository
import com.sai.sports.data.local.SessionAttemptRow
import com.sai.sports.fieldtest.FieldAttemptStore
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.MarigoldButton
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.StatusLine
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The official side of the home screen: the assessment sessions SAI has
 * opened for this athlete, and within each, the tests they may still take.
 *
 * Each open session is a floodlit band (ink, with the marigold Start) so it
 * can never be confused with practice. Shown from the cache straight away,
 * then refreshed, so an athlete with no signal at the ground still sees the
 * session they came for.
 */
@Composable
internal fun SessionsSection(
    athleteId: String?,
    onSessionTest: (sessionId: String, code: String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository(context, AppServices.api(context)) }
    val queue = remember { SyncRepository(context) }

    var cached by remember { mutableStateOf<CachedSessions?>(null) }
    var loading by remember { mutableStateOf(true) }
    var fetchFailed by remember { mutableStateOf(false) }
    var refreshes by remember { mutableIntStateOf(0) }

    LaunchedEffect(athleteId, refreshes) {
        val id = athleteId ?: return@LaunchedEffect
        loading = true
        cached = withContext(Dispatchers.IO) { repository.cached(id) }
        val fresh = withContext(Dispatchers.IO) { repository.refresh(id) }
        fetchFailed = fresh == null
        if (fresh != null) cached = fresh
        loading = false
    }

    // Attempts recorded here but not sent yet still block a second attempt:
    // camera tests from the upload queue, field tests from their own store.
    val queued by remember(athleteId) {
        athleteId?.let(queue::observeSessionAttempts) ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val fieldRows by produceState(initialValue = emptyList<SessionAttemptRow>(), athleteId, refreshes) {
        value = withContext(Dispatchers.IO) { athleteId?.let { FieldAttemptStore(context).sessionRows(it) }.orEmpty() }
    }
    val local = queued + fieldRows

    val current = cached
    val views = current?.let {
        SessionAvailability.views(it, local, repository.wallNow(), repository.elapsedNow())
    }.orEmpty()

    when {
        current == null && loading -> Sheet {
            OfficialLabel(color = AppColors.TextSecondary)
            Text(
                stringResource(R.string.sessions_checking),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
        }

        views.isEmpty() -> Sheet {
            OfficialLabel(color = AppColors.TextSecondary)
            Text(stringResource(R.string.sessions_none), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (fetchFailed && current == null) R.string.sessions_offline else R.string.sessions_none_note
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
            if (fetchFailed) {
                LineButton(text = stringResource(R.string.action_try_again), onClick = { refreshes++ })
            }
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            views.forEach { view -> SessionBand(view, onSessionTest) }
        }
    }
}

@Composable
private fun OfficialLabel(color: Color) {
    if (color == AppColors.Accent) {
        Tag(
            text = stringResource(R.string.home_sessions_title),
            tone = TagTone.Accent,
            icon = AppIcons.Shield,
            modifier = Modifier.semantics { heading() }
        )
    } else {
        Eyebrow(stringResource(R.string.home_sessions_title), modifier = Modifier.semantics { heading() }, color = color)
    }
}

@Composable
private fun SessionBand(view: SessionView, onSessionTest: (String, String) -> Unit) {

    val closes = remember(view.session.endsAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(view.session.endsAtMs))
    }

    Sheet(style = SheetStyle.Field, padding = PaddingValues(18.dp), spacing = 14.dp) {
        OfficialLabel(color = AppColors.Accent)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(view.session.name, style = MaterialTheme.typography.headlineSmall)
            StatusLine(
                text = stringResource(R.string.session_open_until, closes),
                icon = AppIcons.Clock,
                color = AppColors.TextSecondary
            )
            view.session.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            view.tests.forEachIndexed { index, test ->
                SessionTestRow(index + 1, test) { onSessionTest(view.session.id, test.code) }
            }
        }
    }
}

@Composable
private fun SessionTestRow(number: Int, test: SessionTestView, onStart: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .background(AppColors.SurfaceHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(AppColors.SurfaceHighest, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number.toString(), style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                stringResource(Labels.testName(test.code)),
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            when (test.state) {
                SessionTestState.RESUBMIT -> StatusLine(
                    text = stringResource(R.string.session_state_resubmit),
                    icon = AppIcons.Cross,
                    color = AppColors.Danger
                )
                SessionTestState.WAITING_TO_SEND -> StatusLine(
                    text = stringResource(R.string.session_state_waiting),
                    icon = AppIcons.Clock,
                    color = AppColors.TextSecondary
                )
                SessionTestState.SUBMITTED -> StatusLine(
                    text = stringResource(R.string.session_state_submitted),
                    icon = AppIcons.Check,
                    color = AppColors.Success
                )
                SessionTestState.AVAILABLE -> Unit
            }
        }
        // One submission per test: once it is taken, there is no button to press.
        if (test.state.canRecord) {
            MarigoldButton(text = stringResource(R.string.session_start), onClick = onStart)
        }
    }
}
