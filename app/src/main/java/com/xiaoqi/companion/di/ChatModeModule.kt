package com.xiaoqi.companion.di

import com.xiaoqi.companion.core.companion.ChatModePreferenceSink
import com.xiaoqi.companion.core.companion.ChatModePreferenceStore
import com.xiaoqi.companion.core.companion.MemoryRepositoryChatModePreferenceSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * T5 模式偏好的绑定。`ChatModePreferenceStore` 自带进程内冷却 map，
 * 必须是单例，否则每次注入都会重新计冷却、把同一条偏好反复写进记忆库。
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModeModule {

    @Provides
    @Singleton
    fun provideChatModePreferenceSink(
        impl: MemoryRepositoryChatModePreferenceSink,
    ): ChatModePreferenceSink = impl

    @Provides
    @Singleton
    fun provideChatModePreferenceStore(sink: ChatModePreferenceSink): ChatModePreferenceStore =
        ChatModePreferenceStore(sink)
}
