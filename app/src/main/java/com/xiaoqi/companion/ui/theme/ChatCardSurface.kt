package com.xiaoqi.companion.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 聊天域统一卡片容器:统一 [ChatColors.CardSurface] + [MaterialTheme.shapes.medium]。
 *
 * 把原本散在 8 个 Composable 里的 `Surface(shape = ..., color = ..., modifier = fillMaxWidth())`
 * 三行模板收敛到一处。变体(自定义 shape / 缺 fillMaxWidth / 加 clickable)继续用 [Surface]。
 *
 * 描边是 1dp 的 outlineVariant：卡片是纯白、页面底色是紫调纸面，单靠明度差在浅色屏上
 * 容易"糊"在一起，一根几乎看不见的描边就能把层级立住，且比投影安静。
 */
@Composable
fun ChatCardSurface(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = ChatColors.CardSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
        content = content,
    )
}
