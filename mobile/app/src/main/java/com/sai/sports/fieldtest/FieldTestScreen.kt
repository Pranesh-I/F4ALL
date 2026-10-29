package com.sai.sports.fieldtest

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sai.sports.R
import com.sai.sports.auth.AppServices
import com.sai.sports.data.AttemptMode
import com.sai.sports.data.IdentityPassStore
import com.sai.sports.data.SessionAvailability
import com.sai.sports.data.SessionGate
import com.sai.sports.data.SessionRepository
import com.sai.sports.data.SyncRepository
import com.sai.sports.ui.common.ErrorText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.kit.BigNumber
import com.sai.sports.ui.kit.FlowProgress
import com.sai.sports.ui.kit.InkButton
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.ModeTag
import com.sai.sports.ui.kit.OfficialStep
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.RegisterRow
import com.sai.sports.ui.kit.ScoreInk
import com.sai.sports.ui.kit.ScoreMark
import com.sai.sports.ui.kit.SectionRule
import com.sai.sports.ui.kit.Sheet
import com.sai.sports.ui.kit.SheetStyle
import com.sai.sports.ui.kit.StatusLine
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Phase { SETUP, COUNTDOWN, RUNNING, DONE }

/**
 * A shuttle run or an endurance run, from setup to result.
 *
 * Setup says how to mark the course and carry the phone — the environment is
 * part of the measurement. The phone counts down with beeps and times from
 * GO; it ends the run itself (still after the last leg, or 600 m by GPS), and
 * the athlete can stop it. Practice is scored and kept privately; an official
 * run is queued for SAI with its trace and sent when there is a network.
 */
@Composable
fun FieldTestScreen(
    testType: FieldTestType,
    mode: AttemptMode,
    sessionId: String?,
    onBack: () -> Unit,
    onServerResult: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val athleteId = remember { AppServices.session(context).current()?.athleteId }
    val store = remember { FieldAttemptStore(context) }
    val recorder = remember { FieldRecorder(context, testType) }
    val official = mode == AttemptMode.OFFICIAL
    val endurance = testType == FieldTestType.ENDURANCE_RUN

    var phase by remember { mutableStateOf(Phase.SETUP) }
    var countdown by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var liveTurns by remember { mutableIntStateOf(0) }
    var liveDistance by remember { mutableDoubleStateOf(0.0) }
    var gpsAccuracy by remember { mutableStateOf<Float?>(null) }
    var stopRequested by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<FieldAttempt?>(null) }
    var gate by remember { mutableStateOf<SessionGate?>(if (official) null else SessionGate.OPEN) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        locationGranted = it
    }

    // An official run is refused up front if the session's one attempt is taken.
    LaunchedEffect(sessionId) {
        if (!official || sessionId == null || athleteId == null) {
            gate = if (official) SessionGate.UNKNOWN else SessionGate.OPEN
            return@LaunchedEffect
        }
        gate = withContext(Dispatchers.IO) {
            val sessions = SessionRepository(context, AppServices.api(context))
            SessionAvailability.gate(
                sessionId = sessionId,
                testType = testType.name,
                cached = sessions.cached(athleteId),
                local = SyncRepository(context).sessionAttempts(athleteId, sessionId) +
                    store.sessionRows(athleteId).filter { it.sessionId == sessionId },
                wallNowMs = sessions.wallNow(),
                elapsedNowMs = sessions.elapsedNow()
            )
        }
    }

    // GPS is started on the setup screen, so a fix is ready by the countdown.
    LaunchedEffect(locationGranted) {
        if (endurance && locationGranted) recorder.startGps()
        while (endurance && locationGranted && phase == Phase.SETUP) {
            gpsAccuracy = recorder.latestFix?.accuracy
            delay(1000)
        }
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            recorder.stop()
        }
    }

    LaunchedEffect(phase) {
        when (phase) {
            Phase.COUNTDOWN -> {
                val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                recorder.startSensors()
                for (second in COUNTDOWN_SECONDS downTo 1) {
                    countdown = second
                    tones.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    delay(1000)
                }
                recorder.markGo()
                tones.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
                phase = Phase.RUNNING
                delay(600)
                tones.release()
            }

            Phase.RUNNING -> {
                var result: FieldResult? = null
                while (result == null) {
                    delay(500)
                    elapsedMs = recorder.elapsedMs()
                    val trace = recorder.snapshot()
                    val scored = withContext(Dispatchers.Default) {
                        if (endurance) {
                            liveDistance = EnduranceRunAnalyzer.distanceSoFar(trace.fixes.filter { it.tMs >= -5000 })
                            if (liveDistance >= FieldThresholds.ENDURANCE_TARGET_METRES || stopRequested ||
                                elapsedMs > MAX_ENDURANCE_MS
                            ) {
                                EnduranceRunAnalyzer.score(trace.fixes, trace.motion)
                            } else {
                                null
                            }
                        } else {
                            val now = ShuttleRunAnalyzer.score(trace.motion)
                            liveTurns = now.turns ?: liveTurns
                            now.takeIf {
                                it.outcome == FieldOutcome.COMPLETE || stopRequested || elapsedMs > MAX_SHUTTLE_MS
                            }
                        }
                    }
                    result = scored
                }
                recorder.stop()
                val finished = result!!
                val trace = recorder.snapshot()
                val saved = FieldAttempt(
                    id = "field_${System.currentTimeMillis()}",
                    testType = testType,
                    mode = mode,
                    athleteId = athleteId.orEmpty(),
                    recordedAtMs = System.currentTimeMillis(),
                    result = finished,
                    sessionId = sessionId,
                    identityCheckId = if (official && sessionId != null && athleteId != null) {
                        IdentityPassStore(context).current(athleteId, sessionId)?.checkId
                    } else {
                        null
                    }
                )
                withContext(Dispatchers.IO) {
                    // An official run that could not be scored is kept but never
                    // sent: there is nothing for SAI to check.
                    val send = official && finished.outcome == FieldOutcome.COMPLETE
                    if (send) store.saveTrace(saved.id, trace)
                    store.save(if (official && !send) saved.copy(notAccepted = true) else saved)
                }
                FieldSyncWorker.syncSoon(context)
                attempt = saved
                phase = Phase.DONE
            }

            else -> Unit
        }
    }

    // After an official run, follow the attempt until SAI has answered.
    LaunchedEffect(attempt?.id) {
        val id = attempt?.id ?: return@LaunchedEffect
        repeat(30) {
            delay(2000)
            val latest = withContext(Dispatchers.IO) { store.load(id) } ?: return@LaunchedEffect
            attempt = latest
            if (latest.resultId != null || latest.notAccepted || latest.mode == AttemptMode.PRACTICE) {
                return@LaunchedEffect
            }
        }
    }

    Page(header = { TitleBar(stringResource(Labels.testName(testType.name)), onBack.takeIf { phase != Phase.RUNNING }) }) {
        ModeTag(official = official)
        if (official && phase != Phase.SETUP) {
            FlowProgress(
                current = when (phase) {
                    Phase.COUNTDOWN -> OfficialStep.COUNTDOWN
                    Phase.RUNNING -> OfficialStep.TEST
                    else -> OfficialStep.UPLOAD
                },
                fieldTest = true
            )
        }

        when (phase) {
            Phase.SETUP -> Setup(
                testType = testType,
                gate = gate,
                needsLocation = endurance && !locationGranted,
                gpsAccuracy = gpsAccuracy,
                onAskLocation = { askLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                onStart = { phase = Phase.COUNTDOWN },
                noGyroscope = !endurance && !recorder.hasGyroscope
            )

            Phase.COUNTDOWN -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                Text(stringResource(R.string.field_stand_still), style = MaterialTheme.typography.titleLarge)
                BigNumber(countdown.toString())
            }

            Phase.RUNNING -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BigNumber(String.format(Locale.US, "%.1f", elapsedMs / 1000.0))
                Text(
                    if (endurance) {
                        stringResource(R.string.field_distance_so_far, liveDistance.toInt())
                    } else {
                        stringResource(R.string.field_turns_so_far, liveTurns)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                Text(
                    stringResource(if (endurance) R.string.field_running_endurance else R.string.field_running_shuttle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary
                )
                LineButton(
                    text = stringResource(R.string.field_stop),
                    onClick = { stopRequested = true },
                    color = AppColors.Danger,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Phase.DONE -> attempt?.let { done ->
                Outcome(done, onServerResult)
                InkButton(text = stringResource(R.string.action_done), onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Setup(
    testType: FieldTestType,
    gate: SessionGate?,
    needsLocation: Boolean,
    gpsAccuracy: Float?,
    onAskLocation: () -> Unit,
    onStart: () -> Unit,
    noGyroscope: Boolean
) {
    val steps = if (testType == FieldTestType.SHUTTLE_RUN) {
        listOf(R.string.field_shuttle_step_1, R.string.field_shuttle_step_2, R.string.field_shuttle_step_3, R.string.field_shuttle_step_4)
    } else {
        listOf(R.string.field_endurance_step_1, R.string.field_endurance_step_2, R.string.field_endurance_step_3, R.string.field_endurance_step_4)
    } + R.string.field_result_step
    Sheet(spacing = 8.dp) {
        SectionRule(stringResource(R.string.field_setup_title))
        steps.forEachIndexed { index, step -> RegisterRow(label = stringResource(step), index = index + 1, divider = false) }
    }

    if (gate != null && gate != SessionGate.OPEN) {
        ErrorText(
            stringResource(
                when (gate) {
                    SessionGate.SUBMITTED -> R.string.session_blocked_submitted
                    SessionGate.WAITING_TO_SEND -> R.string.session_blocked_waiting
                    SessionGate.CLOSED -> R.string.session_blocked_closed
                    else -> R.string.session_blocked_unknown
                }
            )
        )
        return
    }
    if (noGyroscope) {
        ErrorText(stringResource(R.string.field_no_gyroscope))
        return
    }
    if (needsLocation) {
        Text(stringResource(R.string.field_location_why), style = MaterialTheme.typography.bodyMedium)
        InkButton(text = stringResource(R.string.field_location_allow), onClick = onAskLocation, modifier = Modifier.fillMaxWidth())
        return
    }

    val gpsReady = testType != FieldTestType.ENDURANCE_RUN ||
        (gpsAccuracy != null && gpsAccuracy <= FieldThresholds.ENDURANCE_MAX_ACCURACY_M)
    if (testType == FieldTestType.ENDURANCE_RUN) {
        StatusLine(
            text = if (gpsReady) {
                stringResource(R.string.field_gps_ready, gpsAccuracy!!.toInt())
            } else {
                stringResource(R.string.field_gps_waiting)
            },
            icon = if (gpsReady) AppIcons.Check else AppIcons.Clock,
            color = AppColors.TextPrimary
        )
    }
    InkButton(
        text = stringResource(R.string.field_start),
        onClick = onStart,
        enabled = gate == SessionGate.OPEN && gpsReady,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Outcome(attempt: FieldAttempt, onServerResult: (String) -> Unit) {
    val result = attempt.result
    Sheet(style = if (attempt.mode == AttemptMode.PRACTICE) SheetStyle.Rough else SheetStyle.Paper, spacing = 10.dp) {
        if (result.outcome == FieldOutcome.COMPLETE) {
            ScoreMark(
                value = String.format(Locale.US, if (attempt.testType == FieldTestType.SHUTTLE_RUN) "%.2f" else "%.1f", result.score),
                unit = stringResource(R.string.unit_seconds),
                ink = ScoreInk.Pencil
            )
            if (attempt.testType == FieldTestType.SHUTTLE_RUN) {
                result.legMs?.forEachIndexed { index, leg ->
                    RegisterRow(
                        label = stringResource(R.string.field_leg, index + 1),
                        value = "${String.format(Locale.US, "%.2f", leg / 1000.0)} ${stringResource(R.string.unit_seconds)}",
                        divider = false
                    )
                }
            } else {
                result.paceSecondsPerKm?.let {
                    RegisterRow(
                        label = stringResource(R.string.field_pace),
                        value = stringResource(
                            R.string.field_pace_value,
                            String.format(Locale.US, "%d:%02d", it.toInt() / 60, it.toInt() % 60)
                        ),
                        divider = false
                    )
                }
            }
        } else {
            Text(stringResource(R.string.result_not_scored), style = MaterialTheme.typography.headlineSmall, color = AppColors.Danger)
            Text(stringResource(Labels.invalidReason(result.invalid)), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            stringResource(if (attempt.mode == AttemptMode.PRACTICE) R.string.practice_never_counts else R.string.result_provisional_notice),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary
        )
    }

    if (attempt.mode == AttemptMode.OFFICIAL) {
        when {
            attempt.resultId != null -> {
                StatusLine(text = stringResource(R.string.sync_submitted), icon = AppIcons.Check, color = AppColors.TextPrimary)
                PlainButton(
                    text = stringResource(R.string.sync_see_result),
                    onClick = { onServerResult(attempt.resultId) },
                    icon = AppIcons.Forward
                )
            }
            attempt.notAccepted && result.outcome == FieldOutcome.COMPLETE ->
                ErrorText(stringResource(R.string.sync_not_accepted_hint))
            attempt.notAccepted -> Unit
            // No video here: "saved, and sent when there is signal".
            else -> StatusLine(text = stringResource(R.string.field_waiting_to_send), icon = AppIcons.Clock, color = AppColors.TextSecondary)
        }
    }
}

private const val COUNTDOWN_SECONDS = 3
private const val MAX_SHUTTLE_MS = 35_000L
private const val MAX_ENDURANCE_MS = 20 * 60_000L
