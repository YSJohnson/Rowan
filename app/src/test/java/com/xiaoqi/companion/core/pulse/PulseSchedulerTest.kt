package com.xiaoqi.companion.core.pulse

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * [PulseScheduler] 行为测试(结构与 DreamLoopSchedulerTest 对齐)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulseSchedulerTest {

    private val pulsePreferences: PulsePreferences = mockk()
    private val workScheduler: PulseWorkScheduler = mockk(relaxed = true)
    private val enabled = MutableStateFlow(true)
    private val intervalMinutes = MutableStateFlow(PulsePreferences.DEFAULT_INTERVAL_MINUTES)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        every { pulsePreferences.pulseEnabled } returns enabled
        every { pulsePreferences.pulseIntervalMinutes } returns intervalMinutes
    }

    private fun newScheduler(scope: CoroutineScope = CoroutineScope(testDispatcher)): PulseScheduler =
        PulseScheduler(scope, pulsePreferences, workScheduler)

    @Test
    fun start_enqueuesPeriodicWithDefaultInterval() = runTest(testDispatcher) {
        newScheduler().start()
        advanceUntilIdle()

        verify { workScheduler.enqueuePeriodic(PulseScheduler.UNIQUE_NAME, 240L) }
        verify(exactly = 0) { workScheduler.cancelPeriodic(any()) }
    }

    @Test
    fun disablingSwitch_cancelsPeriodic() = runTest(testDispatcher) {
        val scheduler = newScheduler()
        scheduler.start()
        advanceUntilIdle()

        enabled.value = false
        advanceUntilIdle()

        verify { workScheduler.cancelPeriodic(PulseScheduler.UNIQUE_NAME) }
    }

    @Test
    fun changingInterval_reEnqueuesWithNewValue() = runTest(testDispatcher) {
        val scheduler = newScheduler()
        scheduler.start()
        advanceUntilIdle()

        intervalMinutes.value = 60
        advanceUntilIdle()

        verify { workScheduler.enqueuePeriodic(PulseScheduler.UNIQUE_NAME, 240L) }
        verify { workScheduler.enqueuePeriodic(PulseScheduler.UNIQUE_NAME, 60L) }
    }

    @Test
    fun reEnablingAfterOff_schedulesAgain() = runTest(testDispatcher) {
        val scheduler = newScheduler()
        scheduler.start()
        advanceUntilIdle()

        enabled.value = false
        advanceUntilIdle()
        enabled.value = true
        advanceUntilIdle()

        verify(exactly = 2) { workScheduler.enqueuePeriodic(PulseScheduler.UNIQUE_NAME, 240L) }
    }

    @Test
    fun start_isIdempotent() = runTest(testDispatcher) {
        val scheduler = newScheduler()
        scheduler.start()
        scheduler.start()
        advanceUntilIdle()

        verify(exactly = 1) { workScheduler.enqueuePeriodic(PulseScheduler.UNIQUE_NAME, 240L) }
    }

    @Test
    fun triggerNow_enqueuesOneTimeWithoutTouchingPeriodic() = runTest(testDispatcher) {
        val scheduler = newScheduler()
        scheduler.start()
        advanceUntilIdle()

        scheduler.triggerNow()
        advanceUntilIdle()

        verify { workScheduler.enqueueOneTime(PulseScheduler.UNIQUE_TRIGGER_NAME) }
        verify(exactly = 1) { workScheduler.enqueuePeriodic(any(), any()) }
        verify(exactly = 0) { workScheduler.cancelPeriodic(any()) }
    }

    @Test
    fun triggerNow_worksWhileDisabled() = runTest(testDispatcher) {
        val scheduler = newScheduler()
        // 先让首次（开启状态）发射被消费：`enabled` 是 MutableStateFlow，
        // 在首个值被 collect 前就置 false，DistinctUntilChanged 只看到 false、永远不 cancel。
        enabled.value = true
        scheduler.start()
        advanceUntilIdle()

        enabled.value = false
        advanceUntilIdle()

        scheduler.triggerNow()
        advanceUntilIdle()

        verify { workScheduler.enqueuePeriodic(PulseScheduler.UNIQUE_NAME, 240L) }
        verify { workScheduler.cancelPeriodic(PulseScheduler.UNIQUE_NAME) }
        verify { workScheduler.enqueueOneTime(PulseScheduler.UNIQUE_TRIGGER_NAME) }
    }
}
