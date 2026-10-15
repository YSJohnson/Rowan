package com.xiaoqi.companion.core.pulse

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Pulse 的 Hilt 绑定。
 *
 * 放在 core/pulse 内而不是 di/AppModule,是为了让 T6 的写范围自包含 ——
 * `PulsePolicy` / `PulsePreferences` 都是 `@Inject` 构造,只有两个接口需要显式绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PulseModule {

    @Binds
    @Singleton
    abstract fun bindPulseWorkScheduler(impl: WorkManagerPulseScheduler): PulseWorkScheduler

    @Binds
    @Singleton
    abstract fun bindPulseMemorySource(impl: MemoryDaoPulseMemorySource): PulseMemorySource
}
