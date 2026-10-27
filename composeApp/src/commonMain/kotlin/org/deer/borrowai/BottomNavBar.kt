package org.deer.borrowai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import borrowai.composeapp.generated.resources.Res
import borrowai.composeapp.generated.resources.nav_memories
import borrowai.composeapp.generated.resources.nav_record
import borrowai.composeapp.generated.resources.nav_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class BottomNavItem(val page: Int, val label: StringResource) {
    RECORD(AppShellStateHolder.CAPTURE_PAGE, Res.string.nav_record),
    MEMORIES(AppShellStateHolder.MEMORIES_PAGE, Res.string.nav_memories),
    SETTINGS(AppShellStateHolder.SETTINGS_PAGE, Res.string.nav_settings),
}

internal val BottomNavDockReserve = 74.dp

private val DockSelectedBackground = Color(0xFFE4F2F3)

@Composable
internal fun BottomNavBar(
    selectedPage: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.White.copy(alpha = 0.86f),
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomNavItem.entries.forEach { item ->
                    val selected = item.page == selectedPage
                    BottomNavButton(
                        item = item,
                        selected = selected,
                        onClick = { onSelect(item.page) },
                        modifier = if (selected) Modifier.weight(1f) else Modifier.width(44.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavButton(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = stringResource(item.label)
    Row(
        modifier = modifier
            .height(44.dp)
            .background(
                color = if (selected) DockSelectedBackground else Color.Transparent,
                shape = CircleShape,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            drawNavIcon(item, tint)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

private fun DrawScope.drawNavIcon(item: BottomNavItem, color: Color) {
    val stroke = Stroke(width = 1.8f.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h / 2f)
    when (item) {
        BottomNavItem.RECORD -> {
            drawCircle(color = color, radius = w * 0.40f, center = center, style = stroke)
            drawCircle(color = color, radius = w * 0.16f, center = center)
        }

        BottomNavItem.MEMORIES -> {
            listOf(0.25f, 0.5f, 0.75f).forEach { y ->
                drawCircle(
                    color = color,
                    radius = 1.6f.dp.toPx(),
                    center = Offset(w * 0.18f, h * y),
                )
                drawLine(
                    color = color,
                    start = Offset(w * 0.36f, h * y),
                    end = Offset(w * 0.82f, h * y),
                    strokeWidth = 1.8f.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        BottomNavItem.SETTINGS -> {
            drawCircle(color = color, radius = w * 0.30f, center = center, style = stroke)
            drawCircle(color = color, radius = w * 0.10f, center = center, style = stroke)
            repeat(8) { i ->
                val angle = (i * PI / 4).toFloat()
                val inner = w * 0.38f
                val outer = w * 0.48f
                drawLine(
                    color = color,
                    start = Offset(center.x + inner * cos(angle), center.y + inner * sin(angle)),
                    end = Offset(center.x + outer * cos(angle), center.y + outer * sin(angle)),
                    strokeWidth = 1.8f.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
