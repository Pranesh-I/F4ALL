package com.sai.sports.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sai.sports.R
import com.sai.sports.api.ApiResult
import com.sai.sports.api.AppNotification
import com.sai.sports.auth.AppServices
import com.sai.sports.notify.NotificationText
import com.sai.sports.ui.common.Labels
import com.sai.sports.ui.common.LoadFailed
import com.sai.sports.ui.kit.AppIcons
import com.sai.sports.ui.kit.LineButton
import com.sai.sports.ui.kit.Page
import com.sai.sports.ui.kit.PlainButton
import com.sai.sports.ui.kit.TitleBar
import com.sai.sports.ui.theme.AppColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything SAI has told the athlete, newest first, from their account — so
 * it is the same on any phone they sign in on, and nobody else's.
 *
 * Unread ones carry a marigold dot. Opening one marks it read and goes where
 * it points: a result, or the sessions on the home screen.
 */
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenResult: (String) -> Unit,
    onOpenHome: () -> Unit
) {
    val context = LocalContext.current
    val api = remember { AppServices.api(context) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<AppNotification>?>(null) }
    var nextBefore by remember { mutableStateOf<String?>(null) }
    var unread by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        when (val page = withContext(Dispatchers.IO) { api.notifications() }) {
            is ApiResult.Success -> {
                items = page.value.items
                nextBefore = page.value.nextBefore
                unread = page.value.unreadCount
            }
            is ApiResult.Failure -> error = Labels.failure(page.kind, R.string.error_load)
        }
    }

    fun markRead(ids: List<String>, all: Boolean = false) {
        items = items?.map { if (all || it.id in ids) it.copy(read = true) else it }
        scope.launch {
            val result = withContext(Dispatchers.IO) { api.markNotificationsRead(ids, all) }
            if (result is ApiResult.Success) unread = result.value
        }
    }

    fun open(notification: AppNotification) {
        if (!notification.read) markRead(listOf(notification.id))
        when (val destination = NotificationText.destination(notification)) {
            is NotificationText.Destination.Result -> onOpenResult(destination.resultId)
            NotificationText.Destination.Home -> onOpenHome()
            NotificationText.Destination.Inbox -> Unit
        }
    }

    fun loadOlder() {
        val cursor = nextBefore ?: return
        scope.launch {
            val page = withContext(Dispatchers.IO) { api.notifications(before = cursor) }
            if (page is ApiResult.Success) {
                items = items.orEmpty() + page.value.items
                nextBefore = page.value.nextBefore
            }
        }
    }

    Page(scrollable = false, header = { TitleBar(stringResource(R.string.notifications_title), onBack) }) {
        val current = items
        val failure = error
        when {
            current == null && failure == null -> CircularProgressIndicator(color = AppColors.Accent)
            current == null -> LoadFailed(stringResource(failure!!)) { reload += 1 }
            current.isEmpty() -> Text(
                stringResource(R.string.notifications_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (unread > 0) {
                    item {
                        PlainButton(
                            text = stringResource(R.string.notifications_mark_all),
                            onClick = { markRead(emptyList(), all = true) }
                        )
                    }
                }
                items(current, key = { it.id }) { notification ->
                    NotificationRow(notification) { open(notification) }
                }
                if (nextBefore != null) {
                    item {
                        LineButton(
                            text = stringResource(R.string.notifications_older),
                            onClick = ::loadOlder,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(notification: AppNotification, onClick: () -> Unit) {
    val (title, body) = phrased(notification)
    val time = remember(notification.createdAtMs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(notification.createdAtMs))
    }
    val unreadLabel = stringResource(R.string.notifications_unread)
    val (icon, tint) = when {
        "approved" in notification.eventType -> AppIcons.TrendUp to AppColors.Success
        "rejected" in notification.eventType || "resubmi" in notification.eventType -> AppIcons.TrendDown to AppColors.Danger
        "session" in notification.eventType -> AppIcons.Calendar to AppColors.Accent
        "verified" in notification.eventType || "result" in notification.eventType -> AppIcons.Shield to AppColors.Accent
        else -> AppIcons.Bell to AppColors.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.Surface)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.notifications_open), onClick = onClick)
            .padding(16.dp)
            // Unread is said to a screen reader as well as shown by the dot.
            .semantics(mergeDescendants = true) {
                if (!notification.read) stateDescription = unreadLabel
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = if (notification.read) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary
            )
            Text(body, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            Text(time, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
        if (!notification.read) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(10.dp)
                    .background(AppColors.Accent, CircleShape)
            )
        }
    }
}

/** The app's own phrasing in the athlete's language; the server's only for an unknown event. */
@Composable
private fun phrased(notification: AppNotification): Pair<String, String> {
    val strings = NotificationText.strings(notification.eventType)
        ?: return notification.title to notification.body
    val subject = if (NotificationText.isSessionEvent(notification.eventType)) {
        notification.data["session_name"].orEmpty()
    } else {
        stringResource(Labels.testName(notification.data["test_type"].orEmpty()))
    }
    return stringResource(strings.first, subject) to stringResource(strings.second)
}
