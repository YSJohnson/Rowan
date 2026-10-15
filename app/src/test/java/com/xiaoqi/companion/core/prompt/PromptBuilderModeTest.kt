package com.xiaoqi.companion.core.prompt

import com.xiaoqi.companion.core.companion.ChatMode
import com.xiaoqi.companion.core.companion.model.UserInput
import com.xiaoqi.companion.core.prompt.templates.SystemPersona
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromptBuilderModeTest {

    private val builder = PromptBuilder()

    @Before
    fun setUp() {
        SystemPersona.initForTesting(personaConfig())
    }

    @Test
    fun build_withoutModeArgument_defaultsToListening() {
        val result = builder.build(UserInput.Text("今天有点累"))

        assertEquals(ChatMode.LISTEN, result.mode)
        assertTrue(result.systemPrompt.contains("## 对话模式：倾听"))
        assertFalse(result.systemPrompt.contains("## 对话模式：指引"))
    }

    @Test
    fun build_listenMode_tellsModelNotToGiveAdvice() {
        val result = builder.build(UserInput.Text("今天有点累"), mode = ChatMode.LISTEN)

        assertTrue(result.systemPrompt.contains("不给建议、不分析原因"))
    }

    @Test
    fun build_guideMode_usesGuideSectionOnly() {
        val result = builder.build(UserInput.Text("我该怎么办"), mode = ChatMode.GUIDE)

        assertEquals(ChatMode.GUIDE, result.mode)
        assertTrue(result.systemPrompt.contains("## 对话模式：指引"))
        assertTrue(result.systemPrompt.contains("可以主动给建议"))
        assertFalse(result.systemPrompt.contains("## 对话模式：倾听"))
        assertFalse(result.systemPrompt.contains("不给建议、不分析原因"))
    }

    @Test
    fun build_switchingMode_changesSystemPrompt() {
        val listen = builder.build(UserInput.Text("我该怎么办"), mode = ChatMode.LISTEN)
        val guide = builder.build(UserInput.Text("我该怎么办"), mode = ChatMode.GUIDE)

        assertNotEquals(listen.systemPrompt, guide.systemPrompt)
        assertEquals(listen.userMessage, guide.userMessage)
    }

    @Test
    fun build_modeSectionAppearsExactlyOnceInBothModes() {
        listOf(ChatMode.LISTEN, ChatMode.GUIDE).forEach { mode ->
            val prompt = builder.build(UserInput.Text("hi"), mode = mode).systemPrompt
            assertEquals("$mode 的模式段落应只出现一次", 1, Regex("## 对话模式").findAll(prompt).count())
        }
    }

    @Test
    fun build_guideMode_keepsPersonaAndOtherSections() {
        val result = builder.build(
            input = UserInput.Text("工作上我该怎么办"),
            emotionContext = "情绪：焦虑(intensity 0.7)",
            relationshipContext = "关系：好友(0.6)",
            memories = listOf("- 用户下周三有面试"),
            mode = ChatMode.GUIDE,
        )

        assertTrue(result.systemPrompt.contains("Companion"))
        assertTrue(result.systemPrompt.contains("焦虑"))
        assertTrue(result.systemPrompt.contains("好友"))
        assertTrue(result.systemPrompt.contains("用户下周三有面试"))
        assertTrue(result.systemPrompt.contains("Use search_memory for recall."))
        assertTrue(result.systemPrompt.contains("## 对话模式：指引"))
    }

    @Test
    fun build_listenMode_keepsPersonaAndOtherSections() {
        val result = builder.build(
            input = UserInput.Text("今天有点累"),
            emotionContext = "情绪：疲惫(intensity 0.5)",
            memories = listOf("- 用户最近在准备考试"),
        )

        assertTrue(result.systemPrompt.contains("Companion"))
        assertTrue(result.systemPrompt.contains("疲惫"))
        assertTrue(result.systemPrompt.contains("用户最近在准备考试"))
        assertTrue(result.systemPrompt.contains("Use search_memory for recall."))
    }

    @Test
    fun build_yamlModeSections_overrideBuiltInFallback() {
        SystemPersona.initForTesting(personaConfig(withModeSections = true))

        val listen = builder.build(UserInput.Text("hi"))
        val guide = builder.build(UserInput.Text("hi"), mode = ChatMode.GUIDE)

        assertTrue(listen.systemPrompt.contains("yml-listen-override"))
        assertFalse(listen.systemPrompt.contains("不给建议、不分析原因"))
        assertTrue(guide.systemPrompt.contains("yml-guide-override"))
        assertFalse(guide.systemPrompt.contains("可以主动给建议"))
    }

    @Test
    fun build_modeDoesNotLeakIntoUserMessageOrImageFlags() {
        val text = builder.build(UserInput.Text("今天有点累"), mode = ChatMode.GUIDE)
        assertEquals("今天有点累", text.userMessage)
        assertFalse(text.userMessage.contains("对话模式"))

        val vision = builder.build(
            input = UserInput.Vision("看我的穿搭", "base64data", "image/jpeg"),
            mode = ChatMode.GUIDE,
        )
        assertEquals("看我的穿搭", vision.userMessage)
        assertTrue(vision.hasImage)
        assertEquals(ChatMode.GUIDE, vision.mode)
    }

    companion object {
        private fun personaConfig(withModeSections: Boolean = false): PromptConfig = PromptConfig(
            name = "Companion",
            base = "你是 {{name}}，一个温暖、有生命感的 AI 伴侣。\n",
            sections = buildMap {
                put("emotion", PromptConfig.SectionTemplate("当前情绪状态", "{{emotion_context}}"))
                put("relationship", PromptConfig.SectionTemplate("关系上下文", "{{relationship_context}}"))
                put("memory", PromptConfig.SectionTemplate("相关记忆", "{{memories}}"))
                put("location", PromptConfig.SectionTemplate("设备位置", "{{location_context}}"))
                put("tools", PromptConfig.SectionTemplate("可用工具", "Use search_memory for recall."))
                if (withModeSections) {
                    put("mode_listen", PromptConfig.SectionTemplate("YML倾听", "yml-listen-override"))
                    put("mode_guide", PromptConfig.SectionTemplate("YML指引", "yml-guide-override"))
                }
            },
        )

        @JvmStatic
        @AfterClass
        fun restoreDefaultPersona() {
            SystemPersona.initForTesting(personaConfig())
        }
    }
}
