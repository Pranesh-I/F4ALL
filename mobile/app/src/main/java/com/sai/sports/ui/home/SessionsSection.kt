package com.sai.sports.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sai.sports.R
import com.sai.sports.analyzer.TestType
import com.sai.sports.auth.AppServices
import com.sai.sports.data.CachedSessions
import com.sai.sports.data.SessionAvailability
import com.sai.sports.data.SessionRepository
import com.sai.sports.data.SessionTestState
import com.sai.sports.data.SessionTestView
import com.sai.sports.data.SessionView
import com.sai.sports.data.SyncRepository
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The official side of the home screen: the assessment sessions SAI has
 * opened for this athlete, and within each, the tests they may still take.
 *
 * Shown from the cache straight away, then refreshed — so an athlete with no
 * signal at the ground still sees the session they came for.
 */
@Composable
internal fun SessionsSection(
    athleteId: String?,
    onSessionTest: (sessionId: String, TestType) -> Unit
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

    // Attempts recorded here but not sent yet still block a second attempt.
    val local by remember(athleteId) {
        athleteId?.let(queue::observeSessionAttempts) ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    SectionTitle(stringResource(R.string.home_sessions_title))

    val current = cached
    val views = current?.let {
        SessionAvailability.views(it, local, repository.wallNow(), repository.elapsedNow())
    }.orEmpty()

    when {
        current == null && loading -> Text(stringResource(R.string.sessions_checking))

        views.isEmpty() -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.sessions_none),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(if (fetchFailed && current == null) R.string.sessions_offline else R.string.sessions_none_note),
                    style = MaterialTheme.typography.bodySmall
                )
                if (fetchFailed) {
                    OutlinedButton(onClick = { refreshes++ }) {
                        Text(stringResource(R.string.action_try_again))
                    }
                }
            }
        }

        else -> views.forEach { view -> SessionCard(view, onSessionTest) }
    }
}

@Composable
private fun SessionCard(view: SessionView, onSessionTest: (String, TestType) -> Unit) {

    val closes = remember(view.session.endsAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(view.session.endsAtMs))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(view.session.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.session_open_until, closes),
                style = MaterialTheme.typography.bodySmall
            )
            view.session.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            view.tests.forEach { test ->
                SessionTestRow(test) { onSessionTest(view.session.id, test.testType) }
            }
        }
    }
}

@Composable
private fun SessionTestRow(test: SessionTestView, onStart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(Labels.testName(test.testType)), style = MaterialTheme.typography.bodyLarge)
            when (test.state) {
                SessionTestState.RESUBMIT -> Text(
                    stringResource(R.string.session_state_resubmit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                SessionTestState.WAITING_TO_SEND -> Text(
                    stringResource(R.string.session_state_waiting),
                    style = MaterialTheme.typography.bodySmall
                )
                SessionTestState.SUBMITTED -> Text(
                    stringResource(R.string.session_state_submitted),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32)
                )
                SessionTestState.AVAILABLE -> Unit
            }
        }
        // One submission per test: once it is taken, there is no button to press.
        if (test.state.canRecord) {
            Button(onClick = onStart) { Text(stringResource(R.string.session_start)) }
        }
    }
}
