package com.xiaoqi.companion.core.companion

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前对话模式的进程内共享位（T5 接线用）。
 *
 * [ChatViewModel] 每轮判定后写入，[CompanionRuntime] 在组装 prompt 时读一行。
 * 之所以用共享位而不是给 `send()` 加参数：Runtime 的方法签名被多处调用，
 * 加参数会波及 SendMessageUseCase 与全部测试替身，收益不抵成本。
 *
 * 进程内即焚是刻意的 —— 「说什么情境要建议」属于长期偏好，那条走
 * [ChatModePreferenceStore] 落记忆库；这个位只表达"这一轮用哪个模式"。
 */
@Singleton
class ChatModeHolder @Inject constructor() {

    @Volatile
    var current: ChatMode = ChatMode.DEFAULT

    fun set(mode: ChatMode) {
        current = mode
    }

    fun reset() {
        current = ChatMode.DEFAULT
    }
}
