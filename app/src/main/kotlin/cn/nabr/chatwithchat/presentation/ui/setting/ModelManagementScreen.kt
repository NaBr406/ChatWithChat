package cn.nabr.chatwithchat.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.database.entity.PlatformModelRefreshStatus
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.presentation.common.SettingsMaterialGroup
import cn.nabr.chatwithchat.presentation.common.SettingsTopAppBar
import cn.nabr.chatwithchat.presentation.common.settingsCanvasBackground
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.settingsSwitchColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagementScreen(
    modifier: Modifier = Modifier,
    settingViewModel: SettingViewModelV2 = hiltViewModel(),
    onNavigationClick: () -> Unit
) {
    val state by settingViewModel.modelManagementState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingViewModel.fetchPlatforms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier.settingsCanvasBackground(),
        topBar = {
            SettingsTopAppBar(
                title = stringResource(R.string.model_management),
                onNavigationClick = onNavigationClick
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(state.platforms, key = { it.uid }) { platform ->
                val models = state.modelsByPlatformUid[platform.uid].orEmpty()
                val refreshing = platform.uid in state.refreshingPlatformUids
                PlatformModelGroup(
                    platform = platform,
                    models = models,
                    refreshing = refreshing,
                    onRefreshClick = { settingViewModel.refreshPlatformModels(platform.uid) },
                    onModelEnabledChange = { model, enabled ->
                        settingViewModel.updatePlatformModelEnabled(platform.uid, model.modelId, enabled)
                    },
                    onDefaultClick = { model ->
                        settingViewModel.setPlatformDefaultModel(platform.uid, model.modelId)
                    }
                )
            }
        }
    }
}

@Composable
private fun PlatformModelGroup(
    platform: PlatformV2,
    models: List<PlatformModelV2>,
    refreshing: Boolean,
    onRefreshClick: () -> Unit,
    onModelEnabledChange: (PlatformModelV2, Boolean) -> Unit,
    onDefaultClick: (PlatformModelV2) -> Unit
) {
    // 没有模型的分组里只有一行提示——"暂无可用模型"或刷新失败的原因——折起来等于把它藏了，
    // 所以这类分组进场就是展开的。有模型的默认折起，整页才是一份能一眼扫完的平台清单。
    // fetchPlatformModels 把 platforms 和 modelsByPlatformUid 放在同一次 update 里下发，
    // 所以某个平台第一次组合时它的模型列表已经到位，这里读到的空不是"还没加载完"。
    var isExpanded by rememberSaveable { mutableStateOf(models.isEmpty()) }
    // 刻意不用 `by` 解构：展开的分组下面挂着几十行 ListItem，在组合作用域里读这个逐帧变化的
    // 角度会让它们跟着箭头每帧重组一次。只在 graphicsLayer 里读，旋转就只失效这一层的绘制。
    val arrowRotation = animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "modelGroupArrowRotation"
    )

    SettingsMaterialGroup {
        // 折起来时这一行就是整张卡片，上下内边距得给对称的，否则卡片头重脚轻。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { isExpanded = !isExpanded }
                .padding(start = 24.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = platform.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = settingsMaterialColors().primaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = modelRefreshLabel(platform),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (platform.modelRefreshStatus == PlatformModelRefreshStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        settingsMaterialColors().secondaryLabel
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(
                enabled = !refreshing,
                onClick = onRefreshClick,
                colors = ButtonDefaults.textButtonColors(contentColor = settingsMaterialColors().primaryLabel)
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    modifier = Modifier.padding(start = 6.dp),
                    text = stringResource(R.string.refresh_models)
                )
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (isExpanded) R.string.collapse else R.string.expand),
                tint = settingsMaterialColors().secondaryLabel,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = arrowRotation.value }
            )
        }

        // 高度动画不能是唯一的线索。expandVertically 把"内容总高"在固定时长里插值，也就是说
        // 揭示速度 ∝ 内容高度，而屏幕上看得见的只是面板的头一截：30 行的分组挂在页面底部时，
        // 可见窗口只占内容的一成左右，动画两帧就出画，读起来就是"没有动画"——分组越靠下、模型
        // 越多，这个比例越小，这是同一个原因的两种表现。所以再叠一段定长位移和一整条淡入，
        // 它们的行程跟内容多高、露出多少都无关，面板再长，看得见的那截也有整整 220ms 的动作。
        val slideOffsetPx = with(LocalDensity.current) { MODEL_GROUP_REVEAL_SLIDE.roundToPx() }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = MODEL_GROUP_EXPAND_MILLIS, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
                clip = true
            ) + slideInVertically(
                animationSpec = tween(durationMillis = MODEL_GROUP_EXPAND_MILLIS, easing = FastOutSlowInEasing),
                initialOffsetY = { -slideOffsetPx }
            ) + fadeIn(
                animationSpec = tween(durationMillis = MODEL_GROUP_EXPAND_MILLIS, easing = FastOutSlowInEasing)
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = MODEL_GROUP_COLLAPSE_MILLIS, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
                clip = true
            ) + slideOutVertically(
                animationSpec = tween(durationMillis = MODEL_GROUP_COLLAPSE_MILLIS, easing = FastOutLinearInEasing),
                targetOffsetY = { -slideOffsetPx }
            ) + fadeOut(
                animationSpec = tween(durationMillis = MODEL_GROUP_COLLAPSE_MILLIS, easing = FastOutLinearInEasing)
            )
        ) {
            Column {
                if (models.isEmpty()) {
                    Text(
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 14.dp),
                        text = if (platform.modelRefreshStatus == PlatformModelRefreshStatus.FAILED) {
                            stringResource(R.string.model_refresh_failed_inline, platform.modelRefreshError.orEmpty())
                        } else {
                            stringResource(R.string.no_available_models)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = settingsMaterialColors().secondaryLabel
                    )
                } else {
                    ModelManagementRows(
                        models = models,
                        onModelEnabledChange = onModelEnabledChange,
                        onDefaultClick = onDefaultClick
                    )
                }
            }
        }
    }
}

/**
 * 展开的第一帧只铺一屏装得下的行，剩下的按帧补齐。
 *
 * 两百个模型的平台，点一下要在同一帧里同步组合两百个 ListItem + Switch，这一帧几百毫秒全花在
 * 组合上，手感就是"点了没反应"；揭示动画也跟着一起被这一帧吞掉。先铺一屏、余下每帧补一批，
 * 首帧的代价就跟平台有多少模型无关了。
 *
 * 补齐要等揭示动画收尾：中途长高会让 expandVertically 重设目标高度，动画会当场加速。一屏的量
 * 是按屏高算的，所以补进来的行必定落在可视区外，长高的过程看不见。
 */
@Composable
private fun ModelManagementRows(
    models: List<PlatformModelV2>,
    onModelEnabledChange: (PlatformModelV2, Boolean) -> Unit,
    onDefaultClick: (PlatformModelV2) -> Unit
) {
    val rowBudget = modelRowRenderBudget(LocalConfiguration.current.screenHeightDp)
    // 计数挂在 AnimatedVisibility 的内容里，收起时这段组合连状态一起被丢掉，
    // 下次展开自然又从一屏起步——不用另写一条"折叠时重置"的分支，也就不会在退场动画中途改高度。
    var renderedRowCount by remember(models, rowBudget) {
        mutableIntStateOf(minOf(models.size, rowBudget))
    }
    LaunchedEffect(models, rowBudget) {
        if (renderedRowCount >= models.size) return@LaunchedEffect
        delay(MODEL_GROUP_EXPAND_MILLIS.toLong())
        while (renderedRowCount < models.size) {
            withFrameNanos { }
            renderedRowCount = (renderedRowCount + rowBudget).coerceAtMost(models.size)
        }
    }

    repeat(renderedRowCount) { index ->
        val model = models[index]
        ModelManagementRow(
            model = model,
            onEnabledChange = { enabled -> onModelEnabledChange(model, enabled) },
            onDefaultClick = { onDefaultClick(model) }
        )
        // 比的是整份列表的末尾，所以补齐途中最后一行也带分隔线（它在屏幕外），
        // 补完之后真正的末行才收掉。
        if (index < models.lastIndex) {
            HorizontalDivider(modifier = Modifier.padding(start = 24.dp))
        }
    }
}

@Composable
private fun ModelManagementRow(
    model: PlatformModelV2,
    onEnabledChange: (Boolean) -> Unit,
    onDefaultClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(enabled = model.enabled, onClick = onDefaultClick),
        leadingContent = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (model.isDefault) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = if (model.enabled) settingsMaterialColors().primaryLabel else settingsMaterialColors().tertiaryLabel
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = model.displayName.ifBlank { model.modelId },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = model.description.ifBlank { model.modelId },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Switch(
                checked = model.enabled,
                onCheckedChange = onEnabledChange,
                colors = settingsSwitchColors()
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun modelRefreshLabel(platform: PlatformV2): String = when (platform.modelRefreshStatus) {
    PlatformModelRefreshStatus.SUCCESS -> stringResource(R.string.model_refresh_success)
    PlatformModelRefreshStatus.FAILED -> stringResource(R.string.model_refresh_failed_short)
    else -> stringResource(R.string.model_not_refreshed)
}

private const val MODEL_GROUP_EXPAND_MILLIS = 220
private const val MODEL_GROUP_COLLAPSE_MILLIS = 180

/** 内容随面板落位时走的行程。定值，不跟内容高度走，否则长列表会甩出一段荒唐的位移。 */
private val MODEL_GROUP_REVEAL_SLIDE = 20.dp

/** 两行文本的 ListItem 最小高度 72dp，加 1dp 分隔线。 */
internal const val MODEL_ROW_HEIGHT_DP = 73

/** 屏再矮也得一次铺够几行，否则补齐的批次多到没意义。 */
internal const val MODEL_ROW_MIN_BUDGET = 8

/**
 * 首帧铺多少行：铺满一屏再多两行。多出来的两行是安全边界，避免正好差一行露出卡片的断口。
 * 只要它盖住了一屏，后面按帧补进来的行就一定在可视区之外，长高的过程用户看不见。
 */
internal fun modelRowRenderBudget(screenHeightDp: Int): Int =
    (screenHeightDp / MODEL_ROW_HEIGHT_DP + 2).coerceAtLeast(MODEL_ROW_MIN_BUDGET)
