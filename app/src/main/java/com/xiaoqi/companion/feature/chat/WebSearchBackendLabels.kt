package com.xiaoqi.companion.feature.chat

import com.xiaoqi.companion.core.search.WebSearchBackend

/**
 * 联网搜索后端的显示名。
 *
 * GLM 那条必须写明"复用对话密钥" —— 否则用户会以为还得再去申请一个服务，
 * 而它恰恰是唯一装完即可用的选项。
 */
internal fun WebSearchBackend.displayName(): String = when (this) {
    WebSearchBackend.GLM -> "智谱（复用对话密钥）"
    WebSearchBackend.TAVILY -> "Tavily"
    WebSearchBackend.SERPER -> "Serper (Google)"
}
