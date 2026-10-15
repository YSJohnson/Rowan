package com.xiaoqi.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * 全局主题入口。
 *
 * 配色走 [RowanLightScheme] / [RowanDarkScheme]（品牌紫金，见 [RowanPalette]）；
 * 形状、字体、间距分别由 [CompanionShapes] / [CompanionTypography] / [CompanionSpacing] 定义。
 *
 * [darkTheme] 默认 false —— 调用方从 DataStore 的 themeMode 解析后传入，SYSTEM 模式自行判断
 * `isSystemInDarkTheme()`。默认值保证旧调用点 `CompanionTheme { ... }` 行为不变。
 */
@Composable
fun CompanionTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalCompanionSpacing provides CompanionSpacing()) {
        MaterialTheme(
            colorScheme = if (darkTheme) RowanDarkScheme else RowanLightScheme,
            typography = CompanionTypography,
            shapes = CompanionShapes,
            content = content,
        )
    }
}

/** 便捷读取间距 token：`RowanSpacing.lg`。 */
val RowanSpacing: CompanionSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalCompanionSpacing.current
