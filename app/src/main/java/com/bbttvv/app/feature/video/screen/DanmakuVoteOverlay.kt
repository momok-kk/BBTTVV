package com.bbttvv.app.feature.video.screen

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text
import com.bbttvv.app.feature.video.viewmodel.DanmakuVoteKind
import com.bbttvv.app.feature.video.viewmodel.DanmakuVoteUiState
import kotlin.math.roundToInt

internal typealias DanmakuVoteKeyHandler = (KeyEvent) -> Boolean

private val interactionCardShape = RoundedCornerShape(16.dp)
private val interactionOptionShape = RoundedCornerShape(8.dp)
private val interactionCardSurface = Color(0xFF1B1723)
private val interactionCardBorder = Color(0xFF62556C)
private val interactionOptionSurface = Color(0xFF302738)
private val interactionText = Color(0xFFFAF7FF)
private val interactionMutedText = Color(0xFFC4B9CF)
private val interactionFocus = Color(0xFFEFDDFF)
private val interactionFocusText = Color(0xFF241533)

@Composable
internal fun BoxScope.DanmakuVoteOverlay(
    state: DanmakuVoteUiState,
    videoAspectRatio: Float,
    focusCoordinator: PlayerFocusCoordinator,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onKeyHandlerChanged: (DanmakuVoteKeyHandler?) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val compactDialogWidth = (configuration.screenWidthDp * 0.25f).dp
        .coerceIn(232.dp, 300.dp)
    val compactDialogHeight = (configuration.screenHeightDp * 0.38f).dp
        .coerceIn(190.dp, 280.dp)
    val showing = state as? DanmakuVoteUiState.Showing
    val submitting = state as? DanmakuVoteUiState.Submitting
    val error = state as? DanmakuVoteUiState.RetryableError
    val submitted = state as? DanmakuVoteUiState.Submitted
    val prompt = showing?.prompt ?: submitting?.prompt ?: error?.prompt ?: submitted?.prompt ?: return
    val isGrade = prompt.kind == DanmakuVoteKind.Grade
    val remainingSeconds = remember(prompt.interactionKey, showing?.deadlineElapsedMs) {
        mutableIntStateOf(
            showing?.deadlineElapsedMs
                ?.let { ((it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L }
                ?.toInt()
                ?: 0,
        )
    }
    LaunchedEffect(prompt.interactionKey, showing?.deadlineElapsedMs) {
        val deadline = showing?.deadlineElapsedMs ?: return@LaunchedEffect
        while (true) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            remainingSeconds.intValue = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            if (remainingMs <= 0L) return@LaunchedEffect
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val initialIndex = when {
        submitting != null -> submitting.selectedIndex
        error != null -> error.selectedIndex
        submitted != null -> submitted.selectedIndex
        prompt.selectedOptionId > 0 -> prompt.options.indexOfFirst { it.id == prompt.selectedOptionId }.coerceAtLeast(0)
        else -> 0
    }
    val overlayFocusRequester = remember(prompt.interactionKey) { FocusRequester() }
    val listState = rememberLazyListState()
    val activeIndex = remember(prompt.interactionKey) {
        mutableIntStateOf(initialIndex.coerceIn(0, prompt.options.lastIndex))
    }
    val latestState = rememberUpdatedState(state)
    val latestOnSelect = rememberUpdatedState(onSelect)
    val latestOnRetry = rememberUpdatedState(onRetry)
    val latestOnBack = rememberUpdatedState(onBack)
    val keyHandler = remember(prompt.interactionKey, prompt.options.size) {
        handler@{ native: KeyEvent ->
            val currentState = latestState.value
            val keyState = when (currentState) {
                is DanmakuVoteUiState.Showing -> if (currentState.prompt.selectedOptionId > 0) {
                    DanmakuVoteKeyState.Submitted
                } else DanmakuVoteKeyState.Showing
                is DanmakuVoteUiState.Submitting -> DanmakuVoteKeyState.Submitting
                is DanmakuVoteUiState.RetryableError -> DanmakuVoteKeyState.RetryableError
                is DanmakuVoteUiState.Submitted -> DanmakuVoteKeyState.Submitted
                DanmakuVoteUiState.Hidden -> return@handler false
            }
            val decision = resolveDanmakuVoteKeyDecision(
                action = native.action,
                keyCode = native.keyCode,
                repeatCount = native.repeatCount,
                state = keyState,
                currentIndex = activeIndex.intValue,
                lastIndex = prompt.options.lastIndex,
                horizontal = prompt.kind == DanmakuVoteKind.Grade,
            )
            activeIndex.intValue = decision.nextIndex
            when (decision.command) {
                DanmakuVoteKeyCommand.Select -> latestOnSelect.value(decision.nextIndex)
                DanmakuVoteKeyCommand.Retry -> latestOnRetry.value()
                DanmakuVoteKeyCommand.Dismiss -> latestOnBack.value()
                DanmakuVoteKeyCommand.None -> Unit
            }
            decision.consumed
        }
    }

    LaunchedEffect(prompt.interactionKey, initialIndex) {
        activeIndex.intValue = initialIndex.coerceIn(0, prompt.options.lastIndex)
    }

    DisposableEffect(prompt.interactionKey, overlayFocusRequester) {
        val registration = focusCoordinator.registerDanmakuVoteOverlayTarget(
            object : PlayerFocusTarget {
                override fun tryRequestFocus(): Boolean = runCatching {
                    overlayFocusRequester.requestFocus()
                }.getOrDefault(false)
            },
        )
        onDispose(registration::unregister)
    }

    DisposableEffect(keyHandler) {
        onKeyHandlerChanged(keyHandler)
        onDispose {
            onKeyHandlerChanged(null)
        }
    }

    LaunchedEffect(prompt.interactionKey) {
        repeat(3) {
            if (focusCoordinator.requestFocus(PlayerFocusIntent.FocusDanmakuVoteOverlay)) {
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    LaunchedEffect(prompt.interactionKey, activeIndex.intValue) {
        if (prompt.kind != DanmakuVoteKind.Vote) return@LaunchedEffect
        val index = activeIndex.intValue
        val layout = listState.layoutInfo
        if (layout.visibleItemsInfo.none { it.index == index && it.offset >= 0 && it.offset + it.size <= layout.viewportEndOffset }) {
            listState.scrollToItem(index)
        }
    }

    val cardAlignment = remember(prompt.position) {
        val position = prompt.position
        if (position == null) Alignment.CenterEnd else Alignment { card, viewport, _ ->
            IntOffset(
                (viewport.width * position.xFraction - card.width / 2f).roundToInt().coerceIn(0, (viewport.width - card.width).coerceAtLeast(0)),
                (viewport.height * position.yFraction - card.height / 2f).roundToInt().coerceIn(0, (viewport.height - card.height).coerceAtLeast(0)),
            )
        }
    }
    Box(
        modifier = Modifier.align(Alignment.Center).zIndex(20f).aspectRatio(videoAspectRatio).padding(12.dp),
        contentAlignment = cardAlignment,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = compactDialogWidth)
                .heightIn(max = compactDialogHeight)
                .background(interactionCardSurface, interactionCardShape)
                .border(1.dp, interactionCardBorder, interactionCardShape)
                .padding(12.dp)
                .focusRequester(overlayFocusRequester)
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isGrade) "评分" else "投票",
                    color = interactionFocus,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                if (showing != null && remainingSeconds.intValue > 0) {
                    Text(
                        text = "${remainingSeconds.intValue} 秒",
                        color = interactionMutedText,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = prompt.question,
                color = interactionText,
                fontSize = 20.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (isGrade) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    prompt.options.forEachIndexed { index, option ->
                        val focused = index == activeIndex.intValue
                        val selected = prompt.selectedOptionId > 0 && option.id <= prompt.selectedOptionId
                        val preview = prompt.selectedOptionId == 0 && index <= activeIndex.intValue
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "${option.gradeScore!! / 2}星" }
                                .background(
                                    if (focused) interactionFocus else interactionOptionSurface,
                                    interactionOptionShape,
                                )
                                .border(
                                    width = 2.dp,
                                    color = if (focused) interactionText else Color.Transparent,
                                    shape = interactionOptionShape,
                                )
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (preview || selected) "★" else "☆",
                                color = when {
                                    focused -> interactionFocusText
                                    selected -> Color(0xFFFFD54F)
                                    else -> interactionText
                                },
                                fontSize = 25.sp,
                            )
                        }
                    }
                }
            } else LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                itemsIndexed(prompt.options, key = { _, option -> option.id }) { index, option ->
                    val focused = index == activeIndex.intValue
                    val selected = prompt.selectedOptionId == option.id
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(
                                when {
                                    focused -> interactionFocus
                                    selected -> Color(0xFF234738)
                                    else -> interactionOptionSurface
                                },
                                interactionOptionShape,
                            )
                            .border(
                                width = 2.dp,
                                color = if (focused) interactionText else Color.Transparent,
                                shape = interactionOptionShape,
                            )
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    ) {
                        val suffix = when {
                            submitting?.selectedIndex == index -> " · 提交中"
                            submitted?.selectedIndex == index || selected -> " · 已投票"
                            else -> " · ${option.count}票"
                        }
                        Text(
                            text = option.text + suffix,
                            color = if (focused) interactionFocusText else interactionText,
                            fontSize = 16.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (error != null) {
                Text(error.message, color = Color(0xFFFFB4AB), fontSize = 14.sp)
                Text("确认重试", color = interactionMutedText, fontSize = 14.sp)
            } else if (submitting != null && isGrade) {
                Text("正在提交 ${submitting.selectedIndex + 1} 星", color = interactionMutedText, fontSize = 14.sp)
            } else if (submitted != null) {
                Text(if (isGrade) "打分成功" else "投票成功", color = Color(0xFF9BE8C2), fontSize = 14.sp)
            } else if (prompt.selectedOptionId > 0) {
                Text(if (isGrade) "你已经参与过该打分" else "你已经参与过该投票", color = interactionMutedText, fontSize = 14.sp)
            }
        }
    }
}
