package com.hyx.miao.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import com.hyx.miao.ui.theme.MiaoRadius
import com.hyx.miao.ui.theme.MiaoSize

@Immutable
data class MiaoNavigationItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector = selectedIcon,
)

/**
 * Floating, three-segment navigation used by the main shell. The selected
 * segment is a soft glass highlight rather than Material's default indicator.
 */
@Composable
fun MiaoFloatingNavigationBar(
    items: List<MiaoNavigationItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty()) { "Navigation requires at least one item" }
    require(selectedIndex in items.indices) { "Selected navigation index is out of bounds" }

    val extra = LocalMiaoExtraColors.current
    LiquidGlassCard(
        modifier = modifier,
        cornerRadius = MiaoRadius.ExtraLarge,
        style = MiaoSurfaceStyle.GlassStrong,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MiaoSize.FloatingNavigationHeight)
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val selected = selectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
                    },
                    label = "bottom_nav_content",
                )
                val selectedSurface by animateColorAsState(
                    targetValue = if (selected) extra.glassStrong else Color.Transparent,
                    label = "bottom_nav_surface",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.94f,
                    animationSpec = spring(stiffness = 520f, dampingRatio = 0.82f),
                    label = "bottom_nav_icon_scale",
                )
                val itemShape = RoundedCornerShape(MiaoRadius.ExtraLarge)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .shadow(
                            elevation = if (selected) 6.dp else 0.dp,
                            shape = itemShape,
                            ambientColor = extra.elevatedShadow,
                            spotColor = extra.elevatedShadow,
                        )
                        .clip(itemShape)
                        .background(selectedSurface)
                        .selectable(
                            selected = selected,
                            onClick = { onItemSelected(index) },
                            role = Role.Tab,
                        )
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier
                            .size(MiaoSize.StandardIcon)
                            .scale(iconScale),
                    )
                    Text(
                        text = item.label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
