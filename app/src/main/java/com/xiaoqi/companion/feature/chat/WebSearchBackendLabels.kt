package com.xiaoqi.companion.feature.chat

import com.xiaoqi.companion.core.search.WebSearchBackend

/**
 * 设置页用的后端显示名。
 *
 * 名字里就带上"要不要密钥、密钥从哪来"，因为这正是用户踩过的坑：默认值写着"智谱"，
 * 而用 DeepSeek 的人没有智谱密钥，看到这行字不知道该干什么。
 *
 * 短名（[WebSearchBackend.label]）在 core 层，工具结果标题用它；这里补上密钥提示。
 */
internal fun WebSearchBackend.displayName(): String = when (this) {
    WebSearchBackend.AUTO -> "自动（优先已配密钥，否则免密钥必应）"
    WebSearchBackend.GLM -> "智谱（复用对话密钥）"
    WebSearchBackend.TAVILY -> "Tavily（需密钥）"
    WebSearchBackend.SERPER -> "Serper（需密钥）"
    WebSearchBackend.BING -> "必应（免密钥）"
}
