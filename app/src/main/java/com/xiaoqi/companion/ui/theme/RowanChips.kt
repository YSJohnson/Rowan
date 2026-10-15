package com.xiaoqi.companion.ui.theme

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable

/**
 * FilterChip 的选中配色。
 *
 * Material3 默认把选中态绑在 `secondaryContainer` 上，而本项目的那个位置放的是品牌琥珀金 ——
 * 金色在语义上留给"用户 / 记忆高光"，拿来当选中态会被读成警告色，且与主按钮的紫不一致。
 * 选中态一律改走 primary 容器色，和按钮、开关形成同一套"可操作 = 紫"的语言。
 */
@Composable
fun rowanFilterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
