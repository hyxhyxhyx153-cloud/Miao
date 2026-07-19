package com.hyx.miao.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/** Generous, nested radii approximate Apple's continuous corner language. */
val MiaoShapes = Shapes(
    extraSmall = RoundedCornerShape(MiaoRadius.ExtraSmall),
    small = RoundedCornerShape(MiaoRadius.Small),
    medium = RoundedCornerShape(MiaoRadius.Medium),
    large = RoundedCornerShape(MiaoRadius.Large),
    extraLarge = RoundedCornerShape(MiaoRadius.ExtraLarge),
)
