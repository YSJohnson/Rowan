package com.xiaoqi.companion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 聊天域的表面色 token。
 *
 * 与 [androidx.compose.material3.MaterialTheme.colorScheme] 分开的原因：聊天页有自己一套
 * "气泡 / 输入框 / 卡片"分层，直接复用 surface 会和设置页等页面抢同一档灰阶。
 *
 * 取值跟随 [RowanLightScheme] 的紫调纸面体系（原先是冷蓝灰 #E8EEF7，和品牌紫金脱节）。
 */
object ChatColors {
    /** 通用卡片/弹层底色。 */
    val CardSurface: Color = Color(0xFFFFFFFF)

    /** 输入框、次级填充面。 */
    val InputSurface: Color = Color(0xFFF4F1FA)

    /** 助手气泡：纯白 + 描边，靠留白而非色块区分。 */
    val BubbleAi: Color = Color(0xFFFFFFFF)

    /** 用户气泡：品牌紫的 tonal 容器色，浅到不压正文。 */
    val BubbleUser: Color = Color(0xFFE7E3FD)

    /** 气泡描边，浅到只在需要分离层级时可见。 */
    val BubbleOutline: Color = Color(0xFFE9E4F3)

    /** 列表/区块分隔线。 */
    val Divider: Color = Color(0xFFEFEAF8)

    /** 顶栏/输入栏的"毛玻璃"底色，略透出背景渐变。 */
    val BarOverlay: Color = Color(0xF2F7F5FB)

    /**
     * 强调色（发送键就绪、选中态）。直接取品牌主色，避免各页面各写一份 `Color(0xFF...)`
     * —— 之前输入栏用奶油黄 `#FBF5E7`、气泡用冷蓝，就是这么分叉出去的。
     */
    val Accent: Color = RowanLightScheme.primary

    /** 强调色的容器版（浅底 + 深字），用于 chip / 次级按钮。 */
    val AccentSoft: Color = RowanLightScheme.primaryContainer
}
