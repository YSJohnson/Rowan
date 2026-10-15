package com.xiaoqi.companion.core.pulse

import com.xiaoqi.companion.data.db.dao.MemoryDao
import com.xiaoqi.companion.data.db.entity.MemoryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主动消息的记忆来源。
 *
 * 只注入 [MemoryDao](运行的都是 SELECT),不走 `MemoryRepository` ——
 * 那条写路径同时被 T7 敏感记忆策略改动,Pulse 只需要读。
 */
interface PulseMemorySource {
    suspend fun recentMemories(limit: Int): List<MemoryEntity>
}

@Singleton
class MemoryDaoPulseMemorySource @Inject constructor(
    private val memoryDao: MemoryDao,
) : PulseMemorySource {

    override suspend fun recentMemories(limit: Int): List<MemoryEntity> =
        withContext(Dispatchers.IO) {
            memoryDao.getRecentMemories(limit)
        }
}
