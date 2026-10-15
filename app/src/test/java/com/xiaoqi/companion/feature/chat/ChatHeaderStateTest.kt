package com.xiaoqi.companion.feature.chat

import com.xiaoqi.companion.core.presence.PresenceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderStateTest {

    @Test
    fun resolveCompanionHeaderStatus_whenConfigMissing_showsPendingConfig() {
        assertEquals(
            "待配置",
            resolveCompanionHeaderStatus(
                isConfigReady = false,
                isLoading = false,
                latestActivity = null,
                hasError = false,
                presenceMode = PresenceMode.IDLE,
                presenceLabel = "Rowan 在这里",
            ),
        )
    }

    @Test
    fun resolveCompanionHeaderStatus_duringToolCall_showsActualActivity() {
        assertEquals(
            "正在查询地图",
            resolveCompanionHeaderStatus(
                isConfigReady = true,
                isLoading = true,
                latestActivity = "正在查询地图",
                hasError = false,
                presenceMode = PresenceMode.THINKING,
                presenceLabel = "Rowan 思考中",
            ),
        )
    }

    @Test
    fun resolveCompanionHeaderStatus_afterFailure_showsActionableState() {
        assertEquals(
            "连接异常",
            resolveCompanionHeaderStatus(
                isConfigReady = true,
                isLoading = false,
                latestActivity = null,
                hasError = true,
                presenceMode = PresenceMode.ERROR,
                presenceLabel = "Rowan 恢复中",
            ),
        )
    }

    @Test
    fun resolveCompanionHeaderStatus_whenIdle_stripsBrandPrefixFromPresenceLabel() {
        assertEquals(
            "在这里",
            resolveCompanionHeaderStatus(
                isConfigReady = true,
                isLoading = false,
                latestActivity = null,
                hasError = false,
                presenceMode = PresenceMode.IDLE,
                presenceLabel = "Rowan 在这里",
            ),
        )
    }
}
