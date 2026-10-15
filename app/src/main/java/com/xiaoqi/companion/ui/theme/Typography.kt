package com.xiaoqi.companion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xiaoqi.companion.R

// MiSans (Xiaomi HyperOS 默认字体, 免费可商用) - GB2312 子集化打包
// 子集范围见 tmp-fonts/build_gb2312.py; 生僻字自动回退系统 SansSerif
val MiSansFontFamily = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_semibold, FontWeight.SemiBold),
)

// 15 个 M3 档位全部显式定义：漏掉的档位会回落到 Material 默认（Roboto + 默认字重），
// 中英混排时字号/字重会跳变。display 档用于首页问候语，body 档用于对话正文。
private fun rowanText(
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = MiSansFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val CompanionTypography = Typography(
    displayLarge = rowanText(FontWeight.SemiBold, 44, 52),
    displayMedium = rowanText(FontWeight.SemiBold, 32, 40),
    displaySmall = rowanText(FontWeight.SemiBold, 28, 36),

    headlineLarge = rowanText(FontWeight.SemiBold, 26, 34),
    headlineMedium = rowanText(FontWeight.Medium, 24, 32),
    headlineSmall = rowanText(FontWeight.Medium, 22, 29),

    titleLarge = rowanText(FontWeight.SemiBold, 19, 26),
    titleMedium = rowanText(FontWeight.SemiBold, 17, 24),
    titleSmall = rowanText(FontWeight.Medium, 15, 21),

    // 正文行高比 Material 默认略松（23/16 ≈ 1.44），中文长段落读起来不挤。
    bodyLarge = rowanText(FontWeight.Normal, 17, 25),
    bodyMedium = rowanText(FontWeight.Normal, 16, 23),
    bodySmall = rowanText(FontWeight.Normal, 14, 20),

    labelLarge = rowanText(FontWeight.Medium, 15, 20),
    labelMedium = rowanText(FontWeight.Medium, 13, 18, letterSpacing = 0.2),
    labelSmall = rowanText(FontWeight.Medium, 12, 16, letterSpacing = 0.3),
)
