package com.sai.sports.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sai.sports.ui.theme.AppColors

/** One destination in the bottom bar. */
data class BarItem(val key: String, val label: String, val icon: ImageVector)

/**
 * The app's bottom bar: two tabs, the raised centre action, two tabs.
 *
 * The selected tab is white over a soft orange glow; the others are grey.
 * Every tab keeps its label, because an icon alone is a guess for an athlete
 * who reads slowly. The centre action is a white square, the most prominent
 * thing on the bar, because taking a test is what the app is for.
 */
@Composable
fun AppBottomBar(
    items: List<BarItem>,
    centre: BarItem,
    selected: String,
    onSelect: (String) -> Unit
) {
    val (left, right) = items.take(2) to items.drop(2)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 8.dp)
            .heightIn(min = 72.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        left.forEach { Tab(it, it.key == selected, onSelect) }
        CentreAction(centre, centre.key == selected, onSelect)
        right.forEach { Tab(it, it.key == selected, onSelect) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    item: BarItem,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.key) })
            .drawBehind {
                if (selected) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(AppColors.Accent.copy(alpha = 0.35f), Color.Transparent),
                            center = Offset(size.width / 2, size.height),
                            radius = size.width * 0.55f
                        )
                    )
                }
            }
            .padding(top = 10.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier
                .width(18.dp)
                .height(3.dp)
                .background(if (selected) AppColors.Accent else Color.Transparent, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CentreAction(
    item: BarItem,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.key) })
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .offset(y = (-10).dp)
                .size(54.dp)
                .background(if (selected) AppColors.Accent else AppColors.Inverse, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = if (selected) AppColors.OnAccent else AppColors.OnInverse,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) AppColors.TextPrimary else AppColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.offset(y = (-8).dp)
        )
    }
}
