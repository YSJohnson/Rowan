package com.xiaoqi.companion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 品牌调色板 —— 取自 `docs/brand/rowan-logo.svg` 的两枚月牙：
 * Rowan 紫代表助手本体，琥珀金代表用户自己，"两枚月牙相互环抱"是产品隐喻。
 *
 * 色彩分层：
 * - [BrandViolet] / [BrandGold] 是**识别色**，只用于大面积色块、光晕、渐变和图形，不直接上文字
 *   （紫在纯白上对比度 3.4:1、金只有 2.2:1，做文字会掉到 WCAG AA 以下）。
 * - 需要上文字的场景一律走 [RowanLightScheme] / [RowanDarkScheme] 里的 tonal 变体
 *   （primary / secondary 等已按 4.5:1 压深或提亮）。
 * - 语义色按"角色"分配：紫=助手，金=用户与记忆高光，青绿=记忆/洞察，陶土=提醒与警示。
 *
 * 原来的 Material 主题是冷灰蓝 (#4B647C)，和品牌紫金完全脱节，这是整体观感"不对味"的主因。
 */
object RowanPalette {

    // --- 品牌识别色（勿直接用于文字） ---
    val BrandViolet: Color = Color(0xFF7B6EF6)
    val BrandGold: Color = Color(0xFFC9A96E)
    val BrandInk: Color = Color(0xFF080808)

    // --- 月白（浅色）：紫调纸面而非冷灰 ---
    val MoonPaper: Color = Color(0xFFF7F5FB)
    val MoonCard: Color = Color(0xFFFFFFFF)
    val MoonInk: Color = Color(0xFF1A1726)
    val MoonInkSoft: Color = Color(0xFF6B6580)

    // --- 夜（深色） ---
    val NightPaper: Color = Color(0xFF0E0C16)
    val NightCard: Color = Color(0xFF171426)

    // --- 语义强调色 ---
    val MemoryTeal: Color = Color(0xFF2E7D6E)
    val MemoryTealLight: Color = Color(0xFF8ED8C8)
    val ReminderClay: Color = Color(0xFFB4552F)
    val ReminderClayLight: Color = Color(0xFFE8A183)

    /** 首页/对话页背景：从顶部紫调光晕落到纸面，避免整屏纯色的"白板感"。 */
    fun ambientBackdrop(scheme: ColorScheme): Brush = Brush.verticalGradient(
        0f to scheme.primaryContainer.copy(alpha = 0.55f),
        0.42f to scheme.background,
        1f to scheme.background,
    )

    /** 助手气泡/卡片的高光描边，用紫金双色呼应 logo。 */
    fun companionSheen(scheme: ColorScheme): Brush = Brush.linearGradient(
        listOf(
            scheme.primary.copy(alpha = 0.45f),
            scheme.secondary.copy(alpha = 0.30f),
        ),
    )
}

internal val RowanLightScheme = lightColorScheme(
    primary = Color(0xFF5B4BD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE7E3FD),
    onPrimaryContainer = Color(0xFF1E1740),
    inversePrimary = Color(0xFFB9AEFF),

    secondary = Color(0xFF8A6A2E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF7ECD9),
    onSecondaryContainer = Color(0xFF3A2C12),

    tertiary = RowanPalette.MemoryTeal,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCF2ED),
    onTertiaryContainer = Color(0xFF102E29),

    background = RowanPalette.MoonPaper,
    onBackground = RowanPalette.MoonInk,
    surface = RowanPalette.MoonCard,
    onSurface = RowanPalette.MoonInk,
    surfaceVariant = Color(0xFFEFEAF8),
    onSurfaceVariant = RowanPalette.MoonInkSoft,
    surfaceTint = Color(0xFF5B4BD6),

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7E2F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF9FE),
    surfaceContainer = Color(0xFFF4F1FA),
    surfaceContainerHigh = Color(0xFFEEEAF7),
    surfaceContainerHighest = Color(0xFFE8E3F3),

    inverseSurface = Color(0xFF2C2740),
    inverseOnSurface = Color(0xFFF3F0FA),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    outline = Color(0xFFD9D3E8),
    outlineVariant = Color(0xFFE9E4F3),
    scrim = Color(0xFF000000),
)

internal val RowanDarkScheme = darkColorScheme(
    primary = Color(0xFFB9AEFF),
    onPrimary = Color(0xFF241A5E),
    primaryContainer = Color(0xFF3A2E8C),
    onPrimaryContainer = Color(0xFFE5E0FF),
    inversePrimary = Color(0xFF5B4BD6),

    secondary = Color(0xFFE3C489),
    onSecondary = Color(0xFF3A2C12),
    secondaryContainer = Color(0xFF4E3D1B),
    onSecondaryContainer = Color(0xFFF7E3BE),

    tertiary = RowanPalette.MemoryTealLight,
    onTertiary = Color(0xFF0B2B25),
    tertiaryContainer = Color(0xFF23584E),
    onTertiaryContainer = Color(0xFFD5F2EA),

    background = RowanPalette.NightPaper,
    onBackground = Color(0xFFEDEAF6),
    surface = RowanPalette.NightCard,
    onSurface = Color(0xFFEDEAF6),
    surfaceVariant = Color(0xFF262138),
    onSurfaceVariant = Color(0xFFA9A2C0),
    surfaceTint = Color(0xFFB9AEFF),

    surfaceBright = Color(0xFF35304A),
    surfaceDim = Color(0xFF0A0812),
    surfaceContainerLowest = Color(0xFF090711),
    surfaceContainerLow = Color(0xFF131020),
    surfaceContainer = Color(0xFF1A1729),
    surfaceContainerHigh = Color(0xFF231F35),
    surfaceContainerHighest = Color(0xFF2C2740),

    inverseSurface = Color(0xFFEDEAF6),
    inverseOnSurface = Color(0xFF2C2740),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    outline = Color(0xFF443E5C),
    outlineVariant = Color(0xFF2E2942),
    scrim = Color(0xFF000000),
)
