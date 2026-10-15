package com.xiaoqi.companion.core.prompt.templates

import com.xiaoqi.companion.core.companion.ChatMode

/**
 * 两种对话模式的 system prompt 段落（D4）。
 * 在 system_persona.yml 里定义 `mode_listen` / `mode_guide` section 即可覆盖这里的默认文案，无需改代码。
 */
object ChatModePrompts {

    val LISTEN_SECTION: String = section(
        title = "对话模式：倾听",
        body = """
            - 用户此刻要的是被听见，不是被解决
            - 不给建议、不分析原因、不讲道理、不追问细节；避免"你应该/你可以/不如"这类句式
            - 用一两句话接住 TA 的感受，把话头留回去，最多问一句开放的问题
            - 只有在用户明确要建议时才换到给办法的思路
        """.trimIndent(),
    )

    val GUIDE_SECTION: String = section(
        title = "对话模式：指引",
        body = """
            - 用户明确希望你给出想法和办法，可以主动给建议
            - 先用一句话接住情绪，再给 1-3 条具体、可执行的建议，并说清取舍
            - 建议只能基于用户说过的事实和记忆，不要编造
            - 最后把选择权交回用户，不要替他做决定
        """.trimIndent(),
    )

    fun sectionFor(mode: ChatMode): String = when (mode) {
        ChatMode.LISTEN -> LISTEN_SECTION
        ChatMode.GUIDE -> GUIDE_SECTION
    }

    private fun section(title: String, body: String): String = buildString {
        appendLine()
        appendLine("## $title")
        append(body)
    }
}
