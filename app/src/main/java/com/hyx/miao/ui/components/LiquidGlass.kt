package com.hyx.miao.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyx.miao.ui.theme.LocalMiaoExtraColors
import com.hyx.miao.ui.theme.MiaoRadius
import com.hyx.miao.ui.theme.MiaoSize

enum class MiaoSurfaceStyle { Content, Glass, GlassStrong, Toolbar }

/**
 * Calm content card.  Body content is deliberately opaque enough to remain
 * readable; Liquid Glass is reserved for controls and navigation surfaces.
 * blurRadius remains source-compatible but never blurs children.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 0.dp,
    style: MiaoSurfaceStyle = MiaoSurfaceStyle.Content,
    content: @Composable BoxScope.() -> Unit,
) {
    MiaoSurfaceContainer(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        style = style,
        content = content,
    )
}

@Composable
private fun MiaoSurfaceContainer(
    modifier: Modifier,
    shape: Shape,
    style: MiaoSurfaceStyle,
    content: @Composable BoxScope.() -> Unit,
) {
    val extra = LocalMiaoExtraColors.current
    val isDark = MaterialTheme.colorScheme.background.red < 0.25f
    val surface = when (style) {
        MiaoSurfaceStyle.Content -> extra.card
        MiaoSurfaceStyle.Glass -> extra.glass
        MiaoSurfaceStyle.GlassStrong -> extra.glassStrong
        MiaoSurfaceStyle.Toolbar -> extra.glass
    }
    val border = when (style) {
        MiaoSurfaceStyle.Content -> MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = if (isDark) 0.42f else 0.58f,
        )
        MiaoSurfaceStyle.Toolbar -> extra.divider
        else -> extra.glassBorder
    }
    val elevation = when (style) {
        MiaoSurfaceStyle.Content -> 1.dp
        MiaoSurfaceStyle.Toolbar -> 0.dp
        MiaoSurfaceStyle.Glass -> 8.dp
        MiaoSurfaceStyle.GlassStrong -> 14.dp
    }
    val fill = when (style) {
        MiaoSurfaceStyle.Content -> Brush.linearGradient(listOf(surface, surface))
        MiaoSurfaceStyle.Toolbar -> Brush.verticalGradient(
            listOf(
                surface.copy(alpha = if (isDark) 0.9f else 0.78f),
                surface.copy(alpha = if (isDark) 0.82f else 0.62f),
            ),
        )
        else -> Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (isDark) 0.09f else 0.24f),
                surface,
                surface.copy(alpha = 0.96f),
            ),
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = extra.elevatedShadow,
                spotColor = extra.elevatedShadow,
            )
            .clip(shape)
            .background(fill)
            .border(
                width = when (style) {
                    MiaoSurfaceStyle.Content -> 0.75.dp
                    MiaoSurfaceStyle.Toolbar -> 0.5.dp
                    else -> 1.dp
                },
                brush = Brush.verticalGradient(
                    listOf(border, border.copy(alpha = 0.28f)),
                ),
                shape = shape,
            ),
        content = content,
    )
}

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    MiaoSurfaceContainer(
        modifier = modifier,
        shape = shape,
        style = if (strong) MiaoSurfaceStyle.GlassStrong else MiaoSurfaceStyle.Glass,
        content = content,
    )
}

/** Subtle ambient background, kept below content and controls. */
@Composable
fun MiaoBackdrop(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val extra = LocalMiaoExtraColors.current
    val background = MaterialTheme.colorScheme.background
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier = modifier
                .background(background)
                .drawBehind {
                    val radius = size.minDimension * 0.62f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(extra.accentGlow, Color.Transparent),
                            center = Offset(size.width * 0.88f, size.height * 0.08f),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(size.width * 0.88f, size.height * 0.08f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(extra.accentGlow.copy(alpha = extra.accentGlow.alpha * 0.55f), Color.Transparent),
                            center = Offset(size.width * 0.08f, size.height * 0.82f),
                            radius = radius * 0.75f,
                        ),
                        radius = radius * 0.75f,
                        center = Offset(size.width * 0.08f, size.height * 0.82f),
                    )
                },
            contentAlignment = contentAlignment,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiaoTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    LiquidGlassCard(
        modifier = modifier,
        cornerRadius = 0.dp,
        style = MiaoSurfaceStyle.Toolbar,
    ) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val extra = LocalMiaoExtraColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(MiaoSize.MinimumTouchTarget)
            .clip(RoundedCornerShape(MiaoRadius.Small))
            .background(extra.glassStrong)
            .border(0.8.dp, extra.glassBorder, RoundedCornerShape(MiaoRadius.Small)),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
        ),
        content = content,
    )
}

@Composable
fun PawLoadingIndicator(
    modifier: Modifier = Modifier,
    pawCount: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "paw")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = pawCount.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(pawCount * 180, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "paw_progress",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pawCount) { index ->
            Text(
                text = "•",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (progress.toInt() == index) 1f else 0.24f,
                ),
                modifier = Modifier.size(14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun EmotionKaomoji(kaomoji: String, modifier: Modifier = Modifier) {
    Text(
        text = kaomoji,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
        modifier = modifier.padding(top = 2.dp),
    )
}

@Composable
fun ActionHintBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
    }
}
