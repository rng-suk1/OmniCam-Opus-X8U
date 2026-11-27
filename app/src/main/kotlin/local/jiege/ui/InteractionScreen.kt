package local.jiege.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import local.jiege.settings.LaunchModes
import local.jiege.settings.ModelPresets
import local.jiege.settings.ModuleSettings

/** Tab 5 (交互): Viewfinder controls, per-mode memory, quick-launch key, shutter sound, watermark & spoof. */
@Composable
internal fun InteractionScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
    onOpenKeyModes: () -> Unit,
    onOpenWatermarkModes: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "viewfinder_controls") {
            var trigger by remember { mutableIntStateOf(store.getInt(ModuleSettings.FILTER_PANEL_TRIGGER)) }
            UiGroup(title = "取景与操控") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.VIEWFINDER_HDR,
                    title = "取景框亮度优化",
                    summary = "提升取景画面 HDR 高光亮度",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.PRO_PEAKING_AF,
                    title = "自动对焦也显示峰值",
                    summary = "大师 Pro 开启峰值后，AF 状态同样描边",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.QUICK_EV_RULER,
                    title = "EV 快捷滑条",
                    summary = "大师与理光全屏取景显示曝光标尺，双击归零、长按换边",
                )
                UiDivider()
                CouiMenuRow(
                    title = "拉起快速切换滤镜面板",
                    items = listOf("无", "上滑", "长按"),
                    selectedIndex = trigger,
                    onSelect = { next ->
                        trigger = next
                        store.setInt(ModuleSettings.FILTER_PANEL_TRIGGER, next)
                    },
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.AI_COMPOSITION_CLOUD,
                    title = "AI 灵感构图",
                    summary = "接入系统云端构图推荐与陀螺仪引导",
                )
            }
        }
        item(key = "memory_and_keylaunch") {
            var keyLaunchOn by remember { mutableStateOf(store.get(ModuleSettings.KEY_LAUNCH_MODE_ON)) }
            UiGroup(title = "状态记忆与快启键") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.FLASH_PER_MODE,
                    title = "闪光灯按模式独立",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.LIVE_PHOTO_PER_MODE,
                    title = "实况按模式独立",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.KEY_LAUNCH_MODE_ON,
                    title = "快启键指定模式",
                    summary = "按快启键进入指定模式，不跟随上次退出状态",
                    onChange = { keyLaunchOn = it },
                )
                AnimatedVisibility(visible = keyLaunchOn) {
                    Column {
                        UiDivider()
                        val launchLabel = remember(resumeTick) {
                            LaunchModes.label(store.getString(ModuleSettings.KEY_LAUNCH_MODE))
                        }
                        CouiNavRow(
                            title = "启动模式",
                            summary = launchLabel,
                            onClick = onOpenKeyModes,
                        )
                    }
                }
            }
        }
        item(key = "shutter_sound") {
            var sound by remember { mutableIntStateOf(store.getInt(ModuleSettings.SHUTTER_SOUND)) }
            var always by remember { mutableStateOf(store.get(ModuleSettings.SHUTTER_ALWAYS)) }
            var customVolume by remember { mutableStateOf(store.get(ModuleSettings.SHUTTER_VOLUME_CUSTOM)) }
            UiGroup(title = "快门声") {
                CouiMenuRow(
                    title = "快门音效",
                    items = listOf("现代", "复古", "XPAN", "GR"),
                    selectedIndex = sound,
                    onSelect = { next ->
                        sound = next
                        store.setInt(ModuleSettings.SHUTTER_SOUND, next)
                    },
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.SHUTTER_ALWAYS,
                    title = "静音和震动时播放快门声",
                    summary = "需在 LSPosed 勾选“系统框架”",
                    onChange = { always = it },
                )
                AnimatedVisibility(visible = always) {
                    Column {
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.SHUTTER_IN_SILENT,
                            title = "静音模式下播放",
                        )
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.SHUTTER_IN_VIBRATE,
                            title = "震动模式下播放",
                        )
                    }
                }
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.SHUTTER_KEEP_MEDIA,
                    title = "不打断媒体音",
                    summary = "连接耳机时快门声仅走耳机，不暂停音乐",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.SHUTTER_VOLUME_CUSTOM,
                    title = "快门声独立音量",
                    onChange = { customVolume = it },
                )
                AnimatedVisibility(visible = customVolume) {
                    Column {
                        UiDivider()
                        CouiSettingPercent(
                            store = store,
                            key = ModuleSettings.SHUTTER_VOLUME,
                            title = "快门声音量",
                        )
                    }
                }
            }
        }
        item(key = "watermark_and_spoof") {
            val count = remember(resumeTick) {
                LaunchModes.watermarkModes(store.getString(ModuleSettings.WATERMARK_MODES)).size
            }
            var spoofWatermark by remember { mutableStateOf(store.get(ModuleSettings.MODEL_SPOOF_WATERMARK)) }
            val presetName = remember(resumeTick) {
                ModelPresets.nameFor(
                    store.getString(ModuleSettings.MODEL_PRESET_ID),
                    store.getString(ModuleSettings.MODEL_CUSTOM_PRESETS),
                )
            }
            UiGroup(title = "水印与机型伪装") {
                CouiNavRow(
                    title = "独立保存水印",
                    summary = if (count == 0) "各模式共用" else "$count 个模式独立",
                    onClick = onOpenWatermarkModes,
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.XPAN_WATERMARK_ISOLATION,
                    title = "XPAN 水印隔离",
                    summary = "仅在 XPAN 模式使用专属水印",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.LEICA_WATERMARK_HASSELBLAD_BADGE,
                    title = "徕卡水印使用哈苏徽标",
                    summary = "开启后将徕卡红标与 LEICA 字样替换为哈苏 H 标与 HASSELBLAD",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.MODEL_SPOOF_WATERMARK,
                    title = "水印机型伪装",
                    summary = "替换为下方所选机型预设",
                    onChange = { spoofWatermark = it },
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.MODEL_SPOOF_EXIF,
                    title = "EXIF 伪装",
                    summary = "同步改写照片 EXIF 机型字段",
                    enabled = spoofWatermark,
                )
                UiDivider()
                CouiNavRow(
                    title = "机型预设",
                    summary = presetName,
                    onClick = onOpenPresets,
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.PHOTO_RECIPE_EXIF,
                    title = "相册详情显示拍摄配方",
                    summary = "将模式、色彩引擎、滤镜与柔光写入 EXIF，并在相册上滑详情中展示",
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
