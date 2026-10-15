package com.xiaoqi.companion.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角整体放大一档（原先 10/16/22）：陪伴类产品的图形语言要"软"，
 * 尖锐小圆角会读成工具型应用。extraLarge 供大卡片/底部弹层使用。
 */
val CompanionShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)
