package com.xiaoqi.companion.feature.chat

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoqi.companion.R
import com.xiaoqi.companion.core.llm.ConnectivityResult
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.core.presence.runtime.DreamLoopInterval
import com.xiaoqi.companion.core.presence.runtime.DreamRunObserver
import com.xiaoqi.companion.core.pulse.PulsePreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.ui.components.CompanionTopAppBar
import com.xiaoqi.companion.ui.theme.ChatCardSurface
import com.xiaoqi.companion.ui.theme.ChatStatusColors
import com.xiaoqi.companion.ui.theme.LocalCompanionSpacing
import com.xiaoqi.companion.ui.theme.RowanPalette
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class SettingsScreenState(
    val apiKey: String,
    val provider: LlmProvider,
    val modelName: String,
    val baseUrl: String,
    val message: String?,
    val toolSettings: ChatToolCapabilitySettings,
    val connectivityResult: ConnectivityResult?,
    val isCheckingConnectivity: Boolean,
    val probedModels: List<String>,
    val isProbingModels: Boolean,
    val modelProbeMessage: String?,
    val dreamLoopInterval: DreamLoopInterval,
    val dreamRunState: DreamRunObserver.Snapshot,
    val lastDreamSuccessAtMs: Long,
    val lastDreamSuccessSavedCount: Int,
    val dataJustClearedAt: Long,
    val dataJustClearedCount: Int,
    val pulseEnabled: Boolean,
    val pulseIntervalMinutes: Int,
)

internal data class SettingsScreenActions(
    val viewModel: ChatViewModel,
    val onApiKeyChanged: (String) -> Unit,
    val onProviderChanged: (LlmProvider) -> Unit,
    val onModelNameChanged: (String) -> Unit,
    val onBaseUrlChanged: (String) -> Unit,
    val onSave: () -> Unit,
    val onTestConnection: () -> Unit,
    val onProbeModels: () -> Unit,
    val onDeviceStatusEnabledChanged: (Boolean) -> Unit,
    val onLocationContextEnabledChanged: (Boolean) -> Unit,
    val onWeatherContextEnabledChanged: (Boolean) -> Unit,
    val onReminderToolEnabledChanged: (Boolean) -> Unit,
    val onNotificationEnabledChanged: (Boolean) -> Unit,
    val onSystemToolsEnabledChanged: (Boolean) -> Unit,
    val onDreamLoopIntervalChanged: (DreamLoopInterval) -> Unit,
    val onTriggerDreamLoopNow: () -> Unit,
    val onRequestContextPermissions: () -> Unit,
    val onPulseEnabledChanged: (Boolean) -> Unit,
    val onPulseIntervalChanged: (Int) -> Unit,
    val onBack: () -> Unit,
) {
    companion object {
        fun from(
            viewModel: ChatViewModel,
            onRequestContextPermissions: () -> Unit,
            onBack: () -> Unit,
            onPulseEnabledChanged: (Boolean) -> Unit,
            onPulseIntervalChanged: (Int) -> Unit,
        ): SettingsScreenActions = SettingsScreenActions(
            viewModel = viewModel,
            onApiKeyChanged = viewModel::updateSettingsApiKey,
            onProviderChanged = viewModel::updateSettingsProvider,
            onModelNameChanged = viewModel::updateSettingsModelName,
            onBaseUrlChanged = viewModel::updateSettingsBaseUrl,
            onSave = viewModel::saveSettings,
            onTestConnection = viewModel::checkLlmConnectivity,
            onProbeModels = viewModel::probeLlmModels,
            onDeviceStatusEnabledChanged = viewModel::setDeviceStatusContextEnabled,
            onLocationContextEnabledChanged = viewModel::setLocationContextEnabled,
            onWeatherContextEnabledChanged = viewModel::setWeatherContextEnabled,
            onReminderToolEnabledChanged = viewModel::setReminderToolEnabled,
            onNotificationEnabledChanged = viewModel::setNotificationEnabled,
            onSystemToolsEnabledChanged = viewModel::setSystemToolsEnabled,
            onDreamLoopIntervalChanged = viewModel::setDreamLoopInterval,
            onTriggerDreamLoopNow = viewModel::triggerDreamLoopNow,
            onRequestContextPermissions = onRequestContextPermissions,
            onPulseEnabledChanged = onPulseEnabledChanged,
            onPulseIntervalChanged = onPulseIntervalChanged,
            onBack = onBack,
        )
    }
}

/**
 * Pulse 偏好的取用口。Compose composable 没有构造注入,`PulsePreferences` 又不是 ViewModel
 * (`hiltViewModel()` 取不到 `@Singleton` 普通类),所以在 composable 里用 EntryPoint 取一次,
 * 并交给调用方 `remember` 缓存 —— 不要退化成静态单例,那样测试期没法替换。
 *
 * 接口刻意不加 `internal`:Kotlin 会给内部接口的成员加名字修饰,Dagger 生成的
 * Java 实现就认不出这个方法了。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PulsePreferencesEntryPoint {
    fun pulsePreferences(): PulsePreferences
}

@Composable
private fun rememberPulsePreferences(): PulsePreferences {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PulsePreferencesEntryPoint::class.java,
        ).pulsePreferences()
    }
}

@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dreamLoopInterval by viewModel.dreamLoopInterval.collectAsStateWithLifecycle()
    val dreamRunState by viewModel.dreamRunState.collectAsStateWithLifecycle()
    val lastDreamSuccessAtMs by viewModel.lastDreamSuccessAtMs.collectAsStateWithLifecycle()
    val lastDreamSuccessSavedCount by viewModel.lastDreamSuccessSavedCount.collectAsStateWithLifecycle()
    val pulsePreferences = rememberPulsePreferences()
    val pulseEnabled by pulsePreferences.pulseEnabled.collectAsStateWithLifecycle(initialValue = true)
    val pulseIntervalMinutes by pulsePreferences.pulseIntervalMinutes
        .collectAsStateWithLifecycle(initialValue = PulsePreferences.DEFAULT_INTERVAL_MINUTES)
    val settingsScope = rememberCoroutineScope()
    val contextPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    SettingsScreenContent(
        state = SettingsScreenState(
            apiKey = uiState.settingsApiKey,
            provider = uiState.settingsProvider,
            modelName = uiState.settingsModelName,
            baseUrl = uiState.settingsBaseUrl,
            message = uiState.settingsMessage,
            toolSettings = uiState.toolCapabilitySettings,
            connectivityResult = uiState.connectivityResult,
            isCheckingConnectivity = uiState.isCheckingConnectivity,
            probedModels = uiState.probedModels,
            isProbingModels = uiState.isProbingModels,
            modelProbeMessage = uiState.modelProbeMessage,
            dreamLoopInterval = dreamLoopInterval,
            dreamRunState = dreamRunState,
            lastDreamSuccessAtMs = lastDreamSuccessAtMs,
            lastDreamSuccessSavedCount = lastDreamSuccessSavedCount,
            dataJustClearedAt = uiState.dataJustClearedAt,
            dataJustClearedCount = uiState.dataJustClearedCount,
            pulseEnabled = pulseEnabled,
            pulseIntervalMinutes = pulseIntervalMinutes,
        ),
        actions = SettingsScreenActions.from(
            viewModel = viewModel,
            onRequestContextPermissions = {
                contextPermissionLauncher.launch(contextPermissions())
            },
            onBack = onBack,
            // 改完偏好不用碰 PulseScheduler:它的 collector 在监听同一个 Flow,会自动重排
            onPulseEnabledChanged = { enabled ->
                settingsScope.launch { pulsePreferences.setPulseEnabled(enabled) }
            },
            onPulseIntervalChanged = { minutes ->
                settingsScope.launch { pulsePreferences.setPulseIntervalMinutes(minutes) }
            },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    state: SettingsScreenState,
    actions: SettingsScreenActions,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var hasConsumedInitialDreamState by remember { mutableStateOf(false) }
    var selectedPage by remember { mutableStateOf(SettingsPage.MODEL) }
    val spacing = LocalCompanionSpacing.current

    // 清空类操作完成时弹一次 snackbar:用 dataJustClearedAt 时间戳当 key,避免 count
    // 不变时(理论上不会,稳)重复弹。
    val dataClearTemplate = stringResource(R.string.data_clear_done)
    LaunchedEffect(state.dataJustClearedAt) {
        if (state.dataJustClearedAt > 0L && state.dataJustClearedCount > 0) {
            snackbarHostState.showSnackbar(dataClearTemplate.format(state.dataJustClearedCount))
        }
    }

    // dreamRunState 变化时弹一次 snackbar 反馈;Queued/Succeeded/Failed 各自一次。
    // 用 status 当 key 而不是整个 snapshot,避免 savedCount 改变时(理论上不会,但稳)重复弹。
    val queuedMsg = stringResource(R.string.dream_loop_queued)
    val completedWithCountTemplate = stringResource(R.string.dream_loop_completed_with_count)
    val completedEmptyMsg = stringResource(R.string.dream_loop_completed_empty)
    val failedMsg = stringResource(R.string.dream_loop_failed)
    LaunchedEffect(state.dreamRunState.status) {
        if (!hasConsumedInitialDreamState) {
            hasConsumedInitialDreamState = true
            return@LaunchedEffect
        }
        when (state.dreamRunState.status) {
            DreamRunObserver.Status.QUEUED -> snackbarHostState.showSnackbar(queuedMsg)
            DreamRunObserver.Status.SUCCEEDED -> {
                val msg = if (state.dreamRunState.savedCount > 0) {
                    completedWithCountTemplate.format(state.dreamRunState.savedCount)
                } else {
                    completedEmptyMsg
                }
                snackbarHostState.showSnackbar(msg)
            }
            DreamRunObserver.Status.FAILED -> snackbarHostState.showSnackbar(failedMsg)
            else -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.background(RowanPalette.ambientBackdrop(MaterialTheme.colorScheme)),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CompanionTopAppBar(
                title = "设置",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                navigationContentDescription = "返回",
                onNavigationClick = actions.onBack,
                actionText = "保存",
                onActionClick = actions.onSave,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.section),
        ) {
            SettingsPagePicker(
                selectedPage = selectedPage,
                onSelectedPageChanged = { selectedPage = it },
            )
            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            AnimatedContent(
                modifier = Modifier.weight(1f, fill = true),
                targetState = selectedPage,
                transitionSpec = {
                    fadeIn(tween(RowanMotion.MediumMs)) togetherWith fadeOut(tween(RowanMotion.ShortMs))
                },
                label = "settings-page",
            ) { page ->
                when (page) {
                    SettingsPage.MODEL -> SettingsPageContent { settingsModelPage(state, actions) }
                    SettingsPage.CAPABILITIES -> SettingsPageContent { settingsCapabilitiesPage(state, actions) }
                    SettingsPage.SYSTEM -> SettingsPageContent { settingsSystemPage(state, actions) }
                }
            }
        }
    }
}

@Composable
private fun SettingsPageContent(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(LocalCompanionSpacing.current.section),
        contentPadding = PaddingValues(bottom = LocalCompanionSpacing.current.section),
    ) {
        content()
    }
}

private enum class SettingsPage(
    val label: String,
) {
    MODEL("模型"),
    CAPABILITIES("能力"),
    SYSTEM("系统"),
}

private fun LazyListScope.settingsModelPage(
    state: SettingsScreenState,
    actions: SettingsScreenActions,
) {
    item {
        ModelSummaryCard(state = state)
    }
    item {
        SettingsSectionTitle(
            title = "当前引擎",
            subtitle = "选择主对话使用的 Provider 与模型",
        )
    }
    item {
        SettingsGroupCard {
            SettingsFieldLabel("Provider")
            ProviderPicker(
                provider = state.provider,
                onProviderChanged = actions.onProviderChanged,
            )
            Spacer(Modifier.height(14.dp))
            ModelPicker(
                modelName = state.modelName,
                probedModels = state.probedModels,
                isProbing = state.isProbingModels,
                probeMessage = state.modelProbeMessage,
                onModelNameChanged = actions.onModelNameChanged,
                onProbeModels = actions.onProbeModels,
            )
        }
    }
    item {
        SettingsSectionTitle(
            title = "云端接入",
            subtitle = "填写配置后点击保存或测试连接",
        )
    }
    item {
        SettingsGroupCard {
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = actions.onBaseUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址") },
                readOnly = !state.provider.isCustomEndpoint,
                singleLine = true,
                supportingText = {
                    Text(
                        if (state.provider.isCustomEndpoint) {
                            "自定义端点需要自己填完整地址，含 /v1 等版本段"
                        } else {
                            "${state.provider.displayName()} 的固定端点"
                        }
                    )
                },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = actions.onApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = { Text("保留当前密钥") },
                singleLine = true,
                supportingText = { Text("明文显示便于核对；仅存在本机 DataStore，不会上传") },
            )
            Spacer(Modifier.height(10.dp))
            ConnectivityCheckRow(
                result = state.connectivityResult,
                isChecking = state.isCheckingConnectivity,
                onTest = actions.onTestConnection,
            )
        }
    }
}

@Composable
private fun ModelSummaryCard(state: SettingsScreenState) {
    ChatCardSurface {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "当前：${state.provider.name} · ${state.modelName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val statusText = when {
                state.isCheckingConnectivity -> "正在测试连接"
                else -> state.connectivityResult.summaryLabel()
            }
            SettingsMetaRow(
                label = "状态",
                value = statusText,
            )
            SettingsMetaRow(
                label = "说明",
                value = "主对话与后台任务都走这份云端配置",
            )
        }
    }
}

@Composable
private fun SettingsFieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun LazyListScope.settingsCapabilitiesPage(
    state: SettingsScreenState,
    actions: SettingsScreenActions,
) {
    item {
        SettingsSectionTitle(
            title = "基础能力",
            subtitle = "这些开关控制 Rowan 可读取的环境上下文与工具",
        )
    }
    item {
        SettingsGroupCard {
            SettingsToggleRow(
                title = "设备",
                detail = "电量与网络",
                meta = "本机",
                metaIcon = Icons.Outlined.Smartphone,
                checked = state.toolSettings.deviceStatusEnabled,
                onCheckedChange = actions.onDeviceStatusEnabledChanged,
            )
            DividerSpacer()
            SettingsToggleRow(
                title = "位置",
                detail = "上次授权位置",
                meta = "需授权",
                metaIcon = Icons.Outlined.Lock,
                checked = state.toolSettings.locationContextEnabled,
                onCheckedChange = actions.onLocationContextEnabledChanged,
            )
            DividerSpacer()
            SettingsToggleRow(
                title = "天气",
                detail = "当前天气",
                meta = "需联网",
                metaIcon = Icons.Outlined.Wifi,
                checked = state.toolSettings.weatherContextEnabled,
                onCheckedChange = actions.onWeatherContextEnabledChanged,
            )
            DividerSpacer()
            SettingsToggleRow(
                title = "提醒",
                detail = "本地提醒",
                meta = "",
                checked = state.toolSettings.reminderToolEnabled,
                onCheckedChange = actions.onReminderToolEnabledChanged,
            )
            DividerSpacer()
            SettingsToggleRow(
                title = "通知",
                detail = "提醒推送",
                meta = "需通知",
                metaIcon = Icons.Outlined.NotificationsOff,
                checked = state.toolSettings.notificationEnabled,
                onCheckedChange = actions.onNotificationEnabledChanged,
            )
            DividerSpacer()
            SettingsToggleRow(
                title = "系统工具",
                detail = "记忆/时间/提醒等内置工具",
                meta = "",
                checked = state.toolSettings.systemToolsEnabled,
                onCheckedChange = actions.onSystemToolsEnabledChanged,
            )
            DividerSpacer()
            SettingsToggleRow(
                title = "上下文",
                detail = "时间与近期对话",
                meta = "固定开启",
                checked = true,
                enabled = false,
                locked = true,
                onCheckedChange = {},
            )
        }
    }
    item {
        // 位置/通知权限入口：之前「位置」开关只是 toggle，没有请求/跳转入口，
        // 导致权限没授予时位置拿不到。这里直接跳系统「应用详情 → 权限」页让用户开位置。
        val context = androidx.compose.ui.platform.LocalContext.current
        OutlinedButton(
            onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null),
                )
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("授予位置 / 通知权限（跳转系统设置）")
        }
    }
}

private fun LazyListScope.settingsSystemPage(
    state: SettingsScreenState,
    actions: SettingsScreenActions,
) {
    item {
        DreamLoopSection(
            current = state.dreamLoopInterval,
            runState = state.dreamRunState,
            lastSuccessAtMs = state.lastDreamSuccessAtMs,
            lastSuccessSavedCount = state.lastDreamSuccessSavedCount,
            onIntervalChanged = actions.onDreamLoopIntervalChanged,
            onTriggerNow = actions.onTriggerDreamLoopNow,
        )
    }
    item {
        PulseSection(
            enabled = state.pulseEnabled,
            intervalMinutes = state.pulseIntervalMinutes,
            onEnabledChanged = actions.onPulseEnabledChanged,
            onIntervalChanged = actions.onPulseIntervalChanged,
        )
    }
    item {
        DataTransparencySection(viewModel = actions.viewModel)
    }
    item {
        DebugDiagnosticsSection()
    }
    item {
        AboutSection()
    }
}

@Composable
private fun AboutSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionTitle(
            title = "关于",
            subtitle = "版本与素材声明",
        )
        Text(
            text = stringResource(R.string.font_credit_misans),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DreamLoopSection(
    current: DreamLoopInterval,
    runState: DreamRunObserver.Snapshot,
    lastSuccessAtMs: Long,
    lastSuccessSavedCount: Int,
    onIntervalChanged: (DreamLoopInterval) -> Unit,
    onTriggerNow: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionTitle(
            title = "后台觉察",
            subtitle = "Rowan 会在后台按固定间隔整理近期状态",
        )
        SettingsGroupCard {
            SettingsMetaRow(
                label = "间隔",
                value = current.label(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DreamLoopInterval.entries.forEach { interval ->
                    FilterChip(
                        selected = current == interval,
                        onClick = { onIntervalChanged(interval) },
                        label = { Text(interval.label()) },
                    )
                }
            }
            Text(
                text = when (runState.status) {
                    DreamRunObserver.Status.IDLE -> "待命中"
                    DreamRunObserver.Status.QUEUED -> "已排队"
                    DreamRunObserver.Status.RUNNING -> "运行中"
                    DreamRunObserver.Status.SUCCEEDED -> {
                        if (runState.savedCount > 0) {
                            "已完成 · 新增 ${runState.savedCount} 条"
                        } else {
                            "已完成"
                        }
                    }
                    DreamRunObserver.Status.FAILED -> "失败"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = lastDreamRunLabel(lastSuccessAtMs, lastSuccessSavedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onTriggerNow) {
                Text(stringResource(R.string.dream_loop_trigger_now))
            }
        }
    }
}

@Composable
private fun DreamLoopInterval.label(): String = stringResource(labelRes)

/**
 * 档位只给"看一眼情绪"用的粗略节奏,下限由 `WorkManagerPulseScheduler.MIN_PERIODIC_MINUTES` 兜底,
 * 这里最低 60 分钟远在其上。
 */
private val PulseIntervalMinutes = listOf(60, 120, PulsePreferences.DEFAULT_INTERVAL_MINUTES, 480)

private fun pulseIntervalLabel(minutes: Int): String = "${minutes / 60} 小时"

@Composable
private fun PulseSection(
    enabled: Boolean,
    intervalMinutes: Int,
    onEnabledChanged: (Boolean) -> Unit,
    onIntervalChanged: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionTitle(
            title = "主动关怀",
            subtitle = "只在情绪持续不好时才会出现，不会定时打扰",
        )
        SettingsGroupCard {
            SettingsToggleRow(
                title = "情绪不好时主动关心",
                detail = "关闭后 Rowan 不会主动发消息，仍可以随时找它聊",
                meta = "",
                checked = enabled,
                onCheckedChange = onEnabledChanged,
            )
            SettingsMetaRow(label = "间隔", value = pulseIntervalLabel(intervalMinutes))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseIntervalMinutes.forEach { minutes ->
                    FilterChip(
                        selected = intervalMinutes == minutes,
                        onClick = { onIntervalChanged(minutes) },
                        // 关掉总开关时档位灰置:此时间隔不生效,避免给出"改了就有用"的错觉
                        enabled = enabled,
                        label = { Text(pulseIntervalLabel(minutes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPagePicker(
    selectedPage: SettingsPage,
    onSelectedPageChanged: (SettingsPage) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsPage.entries.forEach { page ->
            FilterChip(
                selected = selectedPage == page,
                onClick = { onSelectedPageChanged(page) },
                label = { Text(page.label) },
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(content: @Composable () -> Unit) {
    ChatCardSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun DividerSpacer() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun ProviderPicker(
    provider: LlmProvider,
    onProviderChanged: (LlmProvider) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LlmProvider.entries.forEach { candidate ->
            FilterChip(
                selected = provider == candidate,
                onClick = { onProviderChanged(candidate) },
                label = { Text(candidate.displayName()) },
            )
        }
    }
}

/**
 * 模型选择：可编辑输入框 + 下拉候选 + "探测模型"。
 *
 * 刻意不做成只读的下拉框 —— 探测依赖服务商是否实现 `/v1/models`，没实现的（部分 Anthropic
 * 兼容端点）只能手填，输入框必须始终可编辑。
 */
@Composable
private fun ModelPicker(
    modelName: String,
    probedModels: List<String>,
    isProbing: Boolean,
    probeMessage: String?,
    onModelNameChanged: (String) -> Unit,
    onProbeModels: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelNameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = { expanded = true },
                        enabled = probedModels.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "选择探测到的模型" },
                    ) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .widthIn(max = 320.dp),
            ) {
                probedModels.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onModelNameChanged(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onProbeModels, enabled = !isProbing) {
                if (isProbing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isProbing) "探测中…" else "探测模型")
            }
            if (!isProbing && probedModels.isNotEmpty()) {
                Text(
                    text = "${probedModels.size} 个可选",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        probeMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingsSectionTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingsToggleRow(
    title: String,
    detail: String,
    meta: String,
    metaIcon: ImageVector? = null,
    checked: Boolean,
    enabled: Boolean = true,
    locked: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
    statusDotColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (metaIcon != null && meta.isNotBlank()) {
                    Icon(
                        imageVector = metaIcon,
                        contentDescription = meta,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (statusDotColor != null) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = statusDotColor)
                    }
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (locked) {
            Text(
                text = if (checked) "开" else "关",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun lastDreamRunLabel(lastSuccessAtMs: Long, lastSavedCount: Int): String {
    if (lastSuccessAtMs == 0L) {
        return stringResource(R.string.dream_loop_last_run_never)
    }
    val ago = relativeTimeAgo(lastSuccessAtMs)
    return if (lastSavedCount > 0) {
        stringResource(R.string.dream_loop_last_run_with_count, ago, lastSavedCount)
    } else {
        stringResource(R.string.dream_loop_last_run_at, ago)
    }
}

private fun relativeTimeAgo(thenMs: Long): String {
    val diffMs = (System.currentTimeMillis() - thenMs).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes} 分钟前"
        minutes < 60L * 24L -> "${TimeUnit.MILLISECONDS.toHours(diffMs)} 小时前"
        else -> "${TimeUnit.MILLISECONDS.toDays(diffMs)} 天前"
    }
}

@Composable
internal fun CapabilityMetaPill(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
        )
    }
}

private fun contextPermissions(): Array<String> =
    buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

@Composable
private fun ConnectivityCheckRow(
    result: ConnectivityResult?,
    isChecking: Boolean,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = !isChecking,
        ) {
            Text("测试连接")
        }
        if (isChecking) {
            RowanLoadingIndicator(
                modifier = Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ConnectivityResultLabel(result)
    }
}

@Composable
private fun ConnectivityResultLabel(result: ConnectivityResult?) {
    val (text, color) = when (result) {
        null -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectivityResult.Success -> {
            "成功 · 延迟 ${result.latencyMs} ms" to ChatStatusColors.SuccessText
        }
        is ConnectivityResult.AuthFailure -> {
            "鉴权失败（${result.statusCode}）" to MaterialTheme.colorScheme.error
        }
        is ConnectivityResult.Unreachable -> {
            "不可达：${result.cause}" to MaterialTheme.colorScheme.error
        }
    }
    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

private fun ConnectivityResult?.summaryLabel(): String =
    when (this) {
        null -> "等待检查"
        is ConnectivityResult.Success -> "连接正常"
        is ConnectivityResult.AuthFailure -> "鉴权失败"
        is ConnectivityResult.Unreachable -> "不可达"
    }

@Composable
private fun DataTransparencySection(viewModel: ChatViewModel) {
    var insightCount by remember { mutableStateOf(0) }
    var memoryCount by remember { mutableStateOf(0) }
    var moodCount by remember { mutableStateOf(0) }
    var pendingClear by remember { mutableStateOf<ClearTarget?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = viewModel.exportAllJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                AppLogger.error(LogTags.App, e, "export_all_failed")
            }
        }
    }

    LaunchedEffect(Unit) {
        insightCount = viewModel.insightCount()
        memoryCount = viewModel.memoryCount()
        moodCount = viewModel.moodSnapshotCount()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(
            title = "数据透明",
            subtitle = "本地存储，可查可改可删",
        )
        ChatCardSurface {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountRow(ClearTarget.Insights.label, insightCount)
                CountRow(ClearTarget.MoodSnapshots.label, moodCount)
                CountRow(ClearTarget.Memories.label, memoryCount)
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(java.util.Date())
                        exportLauncher.launch("rowan_export_$timestamp.json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导出全部为 JSON")
                }
                // 二次确认由 AlertDialog 处理,无需额外标题。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ClearButton(
                        label = "清空洞察",
                        target = ClearTarget.Insights,
                        modifier = Modifier.weight(1f),
                    ) { pendingClear = it }
                    ClearButton(
                        label = "清空情绪快照",
                        target = ClearTarget.MoodSnapshots,
                        modifier = Modifier.weight(1f),
                    ) { pendingClear = it }
                    ClearButton(
                        label = "清空记忆",
                        target = ClearTarget.Memories,
                        modifier = Modifier.weight(1f),
                    ) { pendingClear = it }
                }
            }
        }
    }

    pendingClear?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text("确认清空？") },
            text = {
                Text(
                    "将永久删除所有${target.label}，无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        when (target) {
                            ClearTarget.Insights -> viewModel.clearInsights()
                            ClearTarget.MoodSnapshots -> viewModel.clearMoodSnapshots()
                            ClearTarget.Memories -> viewModel.clearMemories()
                        }
                        pendingClear = null
                        scope.launch {
                            insightCount = viewModel.insightCount()
                            memoryCount = viewModel.memoryCount()
                            moodCount = viewModel.moodSnapshotCount()
                        }
                    },
                ) { Text("确定清空") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingClear = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "• $label",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClearButton(
    label: String,
    target: ClearTarget,
    modifier: Modifier = Modifier,
    onClick: (ClearTarget) -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = { onClick(target) },
        modifier = modifier,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class ClearTarget(val label: String) {
    Insights("洞察"),
    MoodSnapshots("情绪快照"),
    Memories("记忆"),
}
