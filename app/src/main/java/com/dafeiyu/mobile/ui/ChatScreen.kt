package com.dafeiyu.mobile.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.dafeiyu.mobile.data.ChatMessage
import com.dafeiyu.mobile.data.DemoRepository
import com.dafeiyu.mobile.data.AssistantMessageStatus
import com.dafeiyu.mobile.data.AssistantRunStatus
import com.dafeiyu.mobile.data.ChatRunMetrics
import com.dafeiyu.mobile.data.ChatRunStatus
import com.dafeiyu.mobile.data.AssistantContentBlockSnapshot
import com.dafeiyu.mobile.ui.components.FishGlyph
import com.dafeiyu.mobile.ui.components.applyButtonDeformation
import com.dafeiyu.mobile.ui.components.rememberGlassButtonDeformationState
import com.dafeiyu.mobile.ui.components.GlassSurface
import com.dafeiyu.mobile.ui.components.GlassMotion
import com.dafeiyu.mobile.ui.components.GlassMaterialProfile
import com.dafeiyu.mobile.ui.components.LiquidGlassFusionSurface
import com.dafeiyu.mobile.ui.components.LiquidGlassShape
import com.dafeiyu.mobile.ui.components.LocalReduceGlassMotion
import com.dafeiyu.mobile.ui.components.LocalReduceGlassTransparency
import com.dafeiyu.mobile.ui.components.ChatMarkdown
import com.dafeiyu.mobile.ui.components.LocalPageGlassBackdrop
import com.dafeiyu.mobile.ui.components.PageBackdropCapture
import com.dafeiyu.mobile.ui.components.capturePageBackdrop
import com.dafeiyu.mobile.ui.components.rememberPageBackdropCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun ChatScreen(repository: DemoRepository, onModels: () -> Unit, onSettings: () -> Unit = {},
    overlayVisible: Boolean = false, onOverlayProgress: (Float) -> Unit = {},
    onAttachmentsOpening: () -> Unit = {}) {
    var draft by rememberSaveable { mutableStateOf("") }
    var draftRevision by remember { mutableLongStateOf(0L) }
    SideEffect { repository.updateComposerDraft(draft) }
    fun updateDraft(value: String) {
        draft = value
        draftRevision++
        repository.updateComposerDraft(value)
    }
    var sendAwaiting by remember { mutableStateOf(false) }
    val sendScope = rememberCoroutineScope()
    var attachmentsOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    val modelMorph = rememberModelMenuMorphState(modelsOpen)
    var sandboxOpen by remember { mutableStateOf(false) }
    val sandboxMode = when (repository.localSandboxMode) {
        com.dafeiyu.mobile.data.LinuxSandboxContract.READ_ONLY -> SandboxMode.ReadOnly
        com.dafeiyu.mobile.data.LinuxSandboxContract.FULL_ACCESS -> SandboxMode.FullAccess
        else -> SandboxMode.WorkspaceWrite
    }
    val sandboxMorph = rememberModelMenuMorphState(sandboxOpen)
    val latestOverlayCallback by rememberUpdatedState(onOverlayProgress)
    LaunchedEffect(modelMorph, sandboxMorph) {
        snapshotFlow { maxOf(modelMorph.progress, sandboxMorph.progress) }
            .collect { latestOverlayCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { latestOverlayCallback(0f) } }
    var attachmentProgress by remember { mutableFloatStateOf(0f) }
    val attachmentState = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottom > 0
    val conversationId = repository.displayConversationId
    val composerGeneration = repository.composerGeneration
    val listState = key(conversationId, composerGeneration) { rememberLazyListState() }
    val scrollIntent = key(conversationId, composerGeneration) {
        rememberSaveable(saver = ChatScrollIntent.StateSaver) { ChatScrollIntent() }
    }
    val scrollScope = rememberCoroutineScope()
    var returnToLatestJob by remember(scrollIntent) { mutableStateOf<Job?>(null) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val currentConversation = {
        repository.displayConversationId == conversationId &&
            repository.composerGeneration == composerGeneration
    }
    val scrollConnection = remember(scrollIntent) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    scrollIntent.interrupt()
                    returnToLatestJob?.cancel()
                }
                return Offset.Zero
            }
        }
    }
    DisposableEffect(scrollIntent) {
        onDispose { returnToLatestJob?.cancel() }
    }
    val colors = MaterialTheme.colorScheme
    val hasConversation = repository.displayMessages.any { it.role == "user" }
    val lastMessageId = repository.displayMessages.lastOrNull()?.id
    val reducedMotion = LocalReduceGlassMotion.current
    val duration = if (reducedMotion) 0 else GlassMotion.MorphMillis
    val quoteText: (String) -> Unit = { text ->
        val quoted = text.lineSequence().joinToString("\n") { "> $it" }
        updateDraft(if (draft.isBlank()) "$quoted\n\n" else "$draft\n\n$quoted\n\n")
        attachmentsOpen = false
    }
    val runMessage = repository.displayMessages.lastOrNull { it.role == "assistant" }
    // produceState retains its value when effect keys change; reset it with the displayed snapshot.
    val runMetrics by key(conversationId, composerGeneration, runMessage) {
        produceState(runMessage?.let(repository::responseRunMetrics), repository.displayResponding) {
            value = runMessage?.let(repository::responseRunMetrics)
            while (currentConversation() && repository.displayResponding) {
                delay(1000)
                if (currentConversation()) value = runMessage?.let(repository::responseRunMetrics)
            }
        }
    }
    val chatBackdrop = rememberPageBackdropCapture()
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val dockHeight = with(LocalDensity.current) { dockHeightPx.toDp() }

    PredictiveBackHandler(enabled = attachmentsOpen && !modelsOpen && !sandboxOpen && !imeVisible && !overlayVisible) {
        try {
            it.collect { event -> attachmentProgress = if (reducedMotion) 0f else event.progress }
            attachmentsOpen = false
        } catch (_: CancellationException) { attachmentProgress = 0f }
    }
    LaunchedEffect(attachmentsOpen) { if (!attachmentsOpen) attachmentProgress = 0f }
    LaunchedEffect(listState, scrollIntent) {
        var dragObserved = false
        snapshotFlow {
            scrollIntent.revision to
                Triple(scrollIntent.pointerPressed || dragging, listState.isScrollInProgress, listState.canScrollBackward)
        }
            .collect { (_, position) ->
                val (isDragging, isScrolling, awayFromLatest) = position
                if (currentConversation()) {
                    if (isDragging && !dragObserved && !scrollIntent.pointerPressed) {
                        scrollIntent.interrupt()
                        returnToLatestJob?.cancel()
                    }
                    scrollIntent.settle(isDragging, isScrolling, awayFromLatest)
                }
                dragObserved = isDragging
            }
    }
    LaunchedEffect(listState, scrollIntent, lastMessageId, scrollIntent.followingLatest, imeBottom) {
        // Remeasure/new messages may arrive during a scroll; retry only when follow is still intended.
        snapshotFlow {
            currentConversation() && hasConversation && scrollIntent.followingLatest &&
                !scrollIntent.pointerPressed && !dragging && !listState.isScrollInProgress &&
                listState.canScrollBackward
        }.collect { shouldAlign ->
            if (shouldAlign) listState.scrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        // This records only the page underneath the dock. The dock is a sibling, so its lens
        // can sample real chat pixels without recording itself or entering a redraw feedback loop.
        Box(Modifier.fillMaxSize().capturePageBackdrop(chatBackdrop).background(colors.background)) {
        if (hasConversation) {
            Box(Modifier.fillMaxSize()) {
                // Observe DOWN before the list starts dragging; never consume selection or scroll input.
                // Allow scroll to pass through buttons by observing at Initial pass but not consuming.
                LazyColumn(state = listState, reverseLayout = true,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection)
                        .pointerInput(scrollIntent) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                scrollIntent.beginGesture()
                                returnToLatestJob?.cancel()
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    scrollIntent.endGesture()
                                }
                            }
                        },
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp,
                        bottom = dockHeight + 22.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    items(repository.displayMessages.asReversed(), key = { it.id }) { ChatMessageRow(it, repository, quoteText) }
                }
                val showReturnToLatest by remember(listState, scrollIntent) {
                    derivedStateOf {
                        !scrollIntent.followingLatest &&
                            (listState.canScrollBackward || scrollIntent.returningLatest)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(visible = showReturnToLatest,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = dockHeight + 10.dp),
                    enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reducedMotion)),
                    exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reducedMotion))) {
                    ComposerGlassButton(label = "回到最新消息", glyph = "arrowdown",
                        enabled = !scrollIntent.followingLatest && !scrollIntent.returningLatest,
                        tint = colors.surface.copy(alpha = .72f),
                        onClick = {
                            if (currentConversation()) {
                                returnToLatestJob?.cancel()
                                val revision = scrollIntent.requestReturn()
                                returnToLatestJob = scrollScope.launch {
                                    try {
                                        if (currentConversation() && scrollIntent.revision == revision) {
                                            if (reducedMotion) listState.scrollToItem(0)
                                            else listState.animateScrollToItem(0)
                                            if (currentConversation()) {
                                                scrollIntent.finishReturn(revision,
                                                    !listState.canScrollBackward && !scrollIntent.pointerPressed &&
                                                        !dragging && !listState.isScrollInProgress)
                                            }
                                        }
                                    } finally {
                                        scrollIntent.finishReturn(revision, atLatest = false)
                                    }
                                }
                            }
                        })
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(bottom = dockHeight).padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    FishGlyph("deepseek", Modifier.width(62.dp).height(46.dp))
                    Text("今天想聊些什么？", fontSize = 28.sp, lineHeight = 38.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                }
            }
        }
        }
        CompositionLocalProvider(LocalPageGlassBackdrop provides chatBackdrop) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { dockHeightPx = it.height }
            .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 6.dp)) {
            key(conversationId, composerGeneration) {
            ChatComposerGlassDock(runMetrics, chatBackdrop) { drawOwnMaterial, composerModifier ->
            GlassSurface(modifier = composerModifier.fillMaxWidth(),
                cornerRadius = 30.dp, tint = colors.surface.copy(alpha = .26f), draggable = false,
                materialProfile = GlassMaterialProfile.ReadablePanel, drawMaterial = drawOwnMaterial) {
                Column(Modifier.fillMaxWidth()) {
                    SelectedAttachmentStrip(repository, Modifier.padding(horizontal = 14.dp).padding(top = if (repository.attachmentPreviews.isEmpty()) 0.dp else 8.dp))
                    AnimatedVisibility(visible = repository.remoteAttachmentProgress.busy,
                        enter = expandVertically(tween(if (reducedMotion) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)) +
                            fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)),
                        exit = shrinkVertically(tween(if (reducedMotion) 0 else GlassMotion.MorphMillis, easing = GlassMotion.EaseOut)) +
                            fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))) {
                        val progress = repository.remoteAttachmentProgress
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("正在发送附件到电脑 · ${progress.name.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (progress.totalBytes > 0) LinearProgressIndicator(progress = {
                                (progress.receivedBytes.toDouble() / progress.totalBytes).coerceIn(0.0, 1.0).toFloat()
                            }, modifier = Modifier.fillMaxWidth(), color = colors.onSurface)
                            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.onSurface)
                        }
                    }
                    BasicTextField(value = draft, onValueChange = ::updateDraft,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 144.dp)
                            .padding(horizontal = 20.dp, vertical = 18.dp)
                            .onFocusChanged { if (it.isFocused) attachmentsOpen = false }
                            .semantics { contentDescription = "消息输入框" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary), maxLines = 5,
                        decorationBox = { inner -> Box {
                            if (draft.isEmpty()) Text("发送消息…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            inner()
                        } })
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ComposerGlassButton(
                            label = if (attachmentsOpen) "收起附件面板" else "添加照片或附件",
                            glyph = if (attachmentsOpen) "close" else "add",
                            tint = colors.surface.copy(alpha = .35f),
                            onClick = {
                                if (!attachmentsOpen) onAttachmentsOpening()
                                focus.clearFocus(); keyboard?.hide(); attachmentsOpen = !attachmentsOpen
                            },
                        )
                        Box(Modifier.size(48.dp)) {
                            val sandboxDeformation = rememberGlassButtonDeformationState()
                            IconButton(onClick = {
                                focus.clearFocus(); keyboard?.hide(); attachmentsOpen = false
                                modelsOpen = false; sandboxOpen = true
                            }, enabled = !modelMorph.surfaceVisible && !sandboxMorph.surfaceVisible,
                                modifier = Modifier.fillMaxSize()
                                    .applyButtonDeformation(state = sandboxDeformation, circularHit = true,
                                        drawShadow = false)
                                    .onGloballyPositioned { sandboxMorph.anchorBounds = it.boundsInWindow() }
                                    .graphicsLayer { alpha = sandboxMorph.launcherAlpha }
                                    .clip(CircleShape).semantics { contentDescription = "沙箱权限：${sandboxMode.title}" }) {
                                FishGlyph(if (repository.connectionMode == "online")
                                    sandboxGlyphForValue(repository.remoteDshState.projection?.workspace?.settings?.sandbox) ?: "lock"
                                    else sandboxMode.glyph, Modifier.size(19.dp), colors.onSurface)
                            }
                            SandboxMenu(sandboxOpen, sandboxMorph, sandboxMode,
                                onModeSelected = { repository.updateLocalSandboxMode(when (it) {
                                    SandboxMode.ReadOnly -> com.dafeiyu.mobile.data.LinuxSandboxContract.READ_ONLY
                                    SandboxMode.WorkspaceWrite -> com.dafeiyu.mobile.data.LinuxSandboxContract.WORKSPACE_WRITE
                                    SandboxMode.FullAccess -> com.dafeiyu.mobile.data.LinuxSandboxContract.FULL_ACCESS
                                }) }, onDismiss = { sandboxOpen = false },
                                reduceTransparency = LocalReduceGlassTransparency.current,
                                remoteOptions = if (repository.connectionMode == "online") repository.remoteDshState.projection
                                    ?.workspace?.settings?.options.orEmpty().map { it.value to it.label } else null,
                                selectedRemoteValue = repository.remoteDshState.projection?.workspace?.settings?.sandbox,
                                onRemoteSelected = repository::selectRemoteSandbox,
                                localSupportedModes = if (repository.localSandboxVerified) SandboxMode.entries.toSet() else setOf(SandboxMode.FullAccess),
                                localVerificationBusy = repository.localSandboxVerifying,
                                localVerificationDetail = repository.localSandboxDetail,
                                onVerifyLocal = repository::verifyLocalSandbox)
                        }
                        Box(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                            val modelDeformation = rememberGlassButtonDeformationState()
                            val capsule = RoundedCornerShape(50)
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .applyButtonDeformation(state = modelDeformation, drawShadow = false,
                                    consumeDrag = false)
                                .onGloballyPositioned { modelMorph.anchorBounds = it.boundsInWindow() }
                                .graphicsLayer { alpha = modelMorph.launcherAlpha }
                                .clip(capsule)
                                .clickable(enabled = !modelMorph.surfaceVisible && !sandboxMorph.surfaceVisible,
                                    indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    role = Role.Button) {
                                    focus.clearFocus(); keyboard?.hide(); attachmentsOpen = false
                                    sandboxOpen = false; modelsOpen = true
                                }
                                .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val modelLabel = listOfNotNull(repository.displaySelectedModel.name.substringBefore(" ·"),
                                    repository.displayReasoningOption?.label).joinToString(" · ")
                                Text(modelLabel, Modifier.weight(1f), fontSize = 13.sp, color = colors.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(5.dp))
                                FishGlyph("chevronDown", Modifier.size(11.dp), colors.onSurfaceVariant)
                            }
                            ModelMenu(repository, expanded = modelsOpen,
                                onDismiss = { modelsOpen = false }, onManage = onModels,
                                morphState = modelMorph)
                        }
                        val canSend = draft.isNotBlank() || repository.attachmentPreviews.isNotEmpty()
                        ComposerGlassButton(
                            label = when {
                                repository.remoteAttachmentProgress.busy -> "取消附件发送"
                                repository.displayResponding -> "停止生成"
                                repository.workspaceSaveError != null -> "重试保存"
                                repository.sendPending || sendAwaiting -> "正在保存"
                                else -> "发送消息"
                            },
                            glyph = if (repository.displayResponding || repository.remoteAttachmentProgress.busy) "stop"
                                else if (repository.workspaceSaveError != null) "download" else "send",
                            enabled = repository.remoteAttachmentProgress.busy || (!repository.sendPending && !sendAwaiting &&
                                (repository.displayResponding || canSend || repository.workspaceSaveError != null)),
                            tint = if (repository.displayResponding || canSend) colors.primary.copy(alpha = .78f)
                                else colors.surface.copy(alpha = .40f),
                            iconColor = if (repository.displayResponding || canSend) Color.White else colors.onSurfaceVariant,
                            materialProfile = if (repository.displayResponding || canSend) GlassMaterialProfile.AccentButton
                                else GlassMaterialProfile.Button,
                            onClick = {
                                if (repository.displayResponding || repository.remoteAttachmentProgress.busy) repository.cancelResponse()
                                else if (!repository.sendPending && !sendAwaiting) {
                                    val submittedDraft = draft
                                    val submittedRevision = draftRevision
                                    val submittedConversation = repository.displayConversationId
                                    val submittedComposer = repository.composerGeneration
                                    val submittedScrollRevision = scrollIntent.revision
                                    val retrySave = repository.workspaceSaveError != null
                                    sendAwaiting = true
                                    sendScope.launch {
                                        try {
                                            if (retrySave) {
                                                repository.retryWorkspaceSave()
                                            } else if (canSend && repository.sendMessage(submittedDraft)) {
                                                if (repository.displayConversationId == submittedConversation &&
                                                    repository.composerGeneration == submittedComposer) {
                                                    scrollIntent.acceptSend(submittedScrollRevision)
                                                }
                                                if (draftRevision == submittedRevision && draft == submittedDraft &&
                                                    repository.displayConversationId == submittedConversation &&
                                                    repository.composerGeneration == submittedComposer) {
                                                    updateDraft("")
                                                    attachmentsOpen = false
                                                }
                                            }
                                        } finally {
                                            sendAwaiting = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            }
            }
            AnimatedVisibility(visible = attachmentsOpen,
                enter = expandVertically(tween(duration, easing = GlassMotion.EaseOut)) +
                    fadeIn(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)),
                exit = shrinkVertically(tween(duration, easing = GlassMotion.EaseOut)) +
                    fadeOut(GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))) {
                attachmentState.SaveableStateProvider("attachment-tray") {
                    AttachmentTray(repository, Modifier.fillMaxWidth().padding(top = 10.dp)
                        .graphicsLayer { alpha = 1f - attachmentProgress * .4f; translationY = attachmentProgress * 40.dp.toPx() })
                }
            }
        }
        }
    }
}

/** One reveal per real reply: a completed run stays still, and a new run first absorbs the old bar. */
@Composable
private fun ChatComposerGlassDock(
    metrics: ChatRunMetrics?,
    backdrop: PageBackdropCapture,
    composer: @Composable (drawOwnMaterial: Boolean, modifier: Modifier) -> Unit,
) {
    val reducedMotion = LocalReduceGlassMotion.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val eligible = metrics?.firstRoundCompleted == true
    var introducedMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    val reveal = remember {
        Animatable(if (eligible && (introducedMessageId == metrics?.messageId ||
                metrics?.status != ChatRunStatus.Running)) 1f else 0f)
    }
    var shown by remember { mutableStateOf(metrics?.takeIf { eligible }) }
    val latestMetrics by rememberUpdatedState(metrics)
    SideEffect {
        if (eligible && shown?.messageId == metrics?.messageId) shown = metrics
    }
    LaunchedEffect(metrics?.messageId, eligible, reducedMotion) {
        val current = latestMetrics
        if (shown != null && shown?.messageId != current?.messageId) {
            reveal.animateTo(0f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
            shown = null
        }
        if (eligible && current != null) {
            shown = current
            introducedMessageId = current.messageId
            reveal.animateTo(1f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
        } else {
            reveal.animateTo(0f, GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))
            shown = null
        }
    }
    val layoutState = remember { ChatDockLayoutState() }
    var infoSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 8.dp.toPx() }
    val composerRadiusPx = with(density) { 30.dp.toPx() }
    val tint = colors.surface.copy(alpha = .26f)
    LiquidGlassFusionSurface(
        modifier = Modifier.fillMaxWidth().onGloballyPositioned(layoutState::updateDock),
        first = {
            layoutState.composerBounds.takeIf { it.width > 0f && it.height > 0f }?.let {
                LiquidGlassShape(it, composerRadiusPx, tint)
            }
        },
        second = {
            val p = reveal.value
            val composerBounds = layoutState.composerBounds
            if (shown == null || p <= 0f || infoSize.height == 0 || composerBounds.isEmpty) null
            else {
                // This is the same placement/scale as the foreground below, in dock-local pixels.
                val scale = .60f + .40f * p
                val width = infoSize.width.toFloat()
                val height = infoSize.height.toFloat()
                val left = width * .82f * (1f - scale)
                val bottom = composerBounds.top - (gapPx * p).roundToInt() +
                    height * .65f * (1f - p)
                val bounds = Rect(left, bottom - height * scale, left + width * scale, bottom)
                LiquidGlassShape(bounds, minOf(bounds.width, bounds.height) / 2f, tint,
                    alpha = (p / .20f).coerceIn(0f, 1f))
            }
        },
        profile = GlassMaterialProfile.ReadablePanel,
        connection = { 1f - reveal.value },
        capture = backdrop,
    ) { drawOwnMaterial ->
        Column(Modifier.fillMaxWidth()) {
            if (shown != null) {
                ChatRunInfoBar(shown,
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val p = reveal.value
                        val occupied = ((placeable.height + gapPx) * p).roundToInt()
                        layout(placeable.width, occupied) {
                            placeable.placeRelative(0,
                                occupied - placeable.height - (gapPx * p).roundToInt())
                        }
                    }.graphicsLayer {
                        val p = reveal.value
                        alpha = (p / .20f).coerceIn(0f, 1f)
                        scaleX = .60f + .40f * p
                        scaleY = scaleX
                        translationY = size.height * .65f * (1f - p)
                        transformOrigin = TransformOrigin(.82f, 1f)
                    },
                    drawMaterial = drawOwnMaterial,
                    revealProgress = { reveal.value },
                    onMeasuredSize = { infoSize = it })
            }
            composer(drawOwnMaterial, Modifier.onGloballyPositioned(layoutState::updateComposer))
        }
    }
}

@Stable
private class ChatDockLayoutState {
    private var dockCoordinates: LayoutCoordinates? = null
    private var composerCoordinates: LayoutCoordinates? = null
    var composerBounds by mutableStateOf(Rect.Zero)
        private set

    fun updateDock(coordinates: LayoutCoordinates) {
        dockCoordinates = coordinates
        updateBounds()
    }

    fun updateComposer(coordinates: LayoutCoordinates) {
        composerCoordinates = coordinates
        updateBounds()
    }

    private fun updateBounds() {
        val dock = dockCoordinates?.takeIf { it.isAttached } ?: return
        val input = composerCoordinates?.takeIf { it.isAttached } ?: return
        composerBounds = dock.localBoundingBoxOf(input, clipBounds = false)
    }
}

/** A user gesture invalidates work started before it, even if it settles back at index zero. */
@Stable
internal class ChatScrollIntent(initialFollowing: Boolean = true) {
    var followingLatest by mutableStateOf(initialFollowing)
        private set
    var pointerPressed by mutableStateOf(false)
        private set
    var returningLatest by mutableStateOf(false)
        private set
    var revision by mutableLongStateOf(0L)
        private set
    private var userScrollActive = false

    fun interrupt() {
        revision++
        followingLatest = false
        returningLatest = false
        userScrollActive = true
    }

    fun beginGesture() {
        pointerPressed = true
        interrupt()
    }

    fun endGesture() {
        pointerPressed = false
    }

    fun settle(isDragging: Boolean, isScrolling: Boolean, awayFromLatest: Boolean) {
        if (userScrollActive && !pointerPressed && !isDragging && !isScrolling) {
            followingLatest = !awayFromLatest
            userScrollActive = false
        }
    }

    fun requestReturn(): Long {
        revision++
        userScrollActive = false
        followingLatest = false
        returningLatest = true
        return revision
    }

    fun finishReturn(requestRevision: Long, atLatest: Boolean) {
        if (revision == requestRevision && returningLatest) {
            returningLatest = false
            followingLatest = atLatest
        }
    }

    fun acceptSend(submittedRevision: Long) {
        if (revision == submittedRevision && !pointerPressed && !userScrollActive && !returningLatest) {
            followingLatest = true
        }
    }

    companion object {
        val StateSaver = Saver<ChatScrollIntent, Boolean>(
            save = { it.followingLatest },
            restore = { ChatScrollIntent(it) },
        )
    }
}

/** Only the circular control deforms. The composer, text field and toolbar stay fixed. */
@Composable
private fun ComposerGlassButton(
    label: String,
    glyph: String,
    tint: Color,
    enabled: Boolean = true,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 48.dp,
    materialProfile: GlassMaterialProfile = GlassMaterialProfile.Button,
    onClick: () -> Unit,
) {
    val deformation = rememberGlassButtonDeformationState()
    GlassSurface(
        modifier = Modifier.size(size)
            .applyButtonDeformation(enabled = enabled, state = deformation, circularHit = true),
        cornerRadius = size / 2,
        tint = tint,
        buttonDeformation = deformation,
        materialProfile = materialProfile,
    ) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.fillMaxSize().semantics { contentDescription = label }) {
            FishGlyph(if (glyph == "arrowdown") "arrow" else glyph,
                Modifier.size(if (glyph == "stop") 17.dp else 20.dp)
                    .graphicsLayer { rotationZ = if (glyph == "arrowdown") 90f else 0f },
                iconColor)
        }
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage, repository: DemoRepository, onQuote: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val user = message.role == "user"
    val responseText = message.streamSnapshot?.takeIf { it.toolCalls.isNotEmpty() }
        ?.messages?.lastOrNull()?.blocks?.filterIsInstance<AssistantContentBlockSnapshot.Text>()
        ?.joinToString("") { it.text } ?: message.text
    var pendingCode by rememberSaveable(message.id) { mutableStateOf<String?>(null) }
    val saveCode = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val code = pendingCode
        pendingCode = null
        if (uri != null && code != null) repository.exportCode(code, uri)
    }
    val complete = message.streamSnapshot?.let { snapshot ->
        snapshot.isFinal && snapshot.messages.isNotEmpty() &&
            (snapshot.runStatus == null || snapshot.runStatus == AssistantRunStatus.COMPLETED) &&
            snapshot.messages.all { it.status == AssistantMessageStatus.COMPLETED }
    } == true
    var choosingResponseLocation by rememberSaveable(message.id) { mutableStateOf(false) }
    val latestExportMessage by rememberUpdatedState(message.copy(text = responseText))
    val responseExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        choosingResponseLocation = false
        if (uri != null) repository.exportResponse(latestExportMessage, uri)
    }
    val exportReply: (() -> Unit)? = if (!user && complete && responseText.isNotBlank()) ({
        choosingResponseLocation = true
        try { responseExport.launch("大肥鱼回复-${message.id}.txt") }
        catch (_: Exception) {
            choosingResponseLocation = false
            repository.updateNotice("无法打开保存选择器，请重试")
        }
    }) else null
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!user) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FishGlyph("deepseek", Modifier.width(24.dp).height(18.dp))
            Text("大肥鱼", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
        }
        if (!user) message.streamSnapshot?.let { snapshot ->
            val retryScope = rememberCoroutineScope()
            val canRetry = repository.canRetryResponse(message)
            AssistantActivityFeed(message.id, snapshot, repository.officialEngineShowReasoning,
                repository.officialEngineExpandActivities,
                onRetry = if (canRetry) ({
                    retryScope.launch { repository.retryFailedResponse(message.id) }
                }) else null,
                retryEnabled = !repository.displayResponding && !repository.sendPending)
        }
        MessageAttachmentStrip(repository, message, Modifier.fillMaxWidth())
        if (user) {
            SelectionContainer {
                Text(message.text, modifier = Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(22.dp))
                    .background(colors.surface).padding(horizontal = 16.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            }
        } else {
            MessageSelectionContainer(onReferenceText = onQuote,
                exportLabel = "导出完整回复为文本", onExport = exportReply,
                exportEnabled = !choosingResponseLocation) {
                val snapshot = message.streamSnapshot
                val streaming = snapshot?.let {
                    !it.isFinal && it.messages.any { part -> part.status == AssistantMessageStatus.IN_PROGRESS }
                } ?: (repository.displayResponding && repository.displayMessages.lastOrNull()?.id == message.id)
                ChatMarkdown(responseText, streaming, "message-${message.id}",
                    modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface, onSaveCode = { code, _ ->
                        if (pendingCode == null) {
                            pendingCode = code
                            try { saveCode.launch("代码-${message.id}.txt") }
                            catch (_: Exception) {
                                pendingCode = null
                                repository.updateNotice("无法打开保存选择器，请重试")
                            }
                        }
                    })
            }
        }
        if (!user) GeneratedFileStrip(repository, message) { filename, text ->
            onQuote("$filename\n$text")
        }
    }
}

/** Selection still belongs to Compose; quote consumes that exact selection without touching system clipboard. */
@Composable
internal fun MessageSelectionContainer(
    onReferenceText: ((String) -> Unit)? = null,
    exportLabel: String = "导出文本文件",
    onExport: (() -> Unit)? = null,
    exportEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val latestReference by rememberUpdatedState(onReferenceText)
    val referenceClipboard = remember(clipboard) {
        SelectionReferenceClipboard(clipboard) { latestReference?.invoke(it) }
    }
    val toolbar = remember { MessageSelectionToolbar() }
    var rootToWindow by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(toolbar, referenceClipboard) {
        onDispose { toolbar.hide(); referenceClipboard.captureReference = false }
    }
    Box(Modifier.onGloballyPositioned {
        rootToWindow = it.findRootCoordinates().localToWindow(Offset.Zero)
    }) {
        CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboard provides referenceClipboard) {
            SelectionContainer(content = content)
        }
        MessageSelectionMenu(toolbar, rootToWindow, canReference = onReferenceText != null,
            exportLabel = exportLabel, onExport = onExport, exportEnabled = exportEnabled,
            onReference = { copy ->
                referenceClipboard.captureReference = true
                try { copy() }
                catch (error: Exception) {
                    referenceClipboard.captureReference = false
                    throw error
                }
            },
            beforeCopy = { referenceClipboard.captureReference = false })
    }
}

private data class MessageSelectionActions(
    val rect: Rect,
    val copy: (() -> Unit)?,
    val selectAll: (() -> Unit)?,
)

@Stable
private class MessageSelectionToolbar : TextToolbar {
    var actions by mutableStateOf<MessageSelectionActions?>(null)
        private set
    override val status: TextToolbarStatus
        get() = if (actions == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        if (rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite()) {
            actions = MessageSelectionActions(rect, onCopyRequested, onSelectAllRequested)
        }
    }

    override fun hide() { actions = null }
}

private class SelectionReferenceClipboard(
    private val delegate: Clipboard,
    private val reference: (String) -> Unit,
) : Clipboard {
    var captureReference = false
    override val nativeClipboard get() = delegate.nativeClipboard
    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (captureReference) {
            captureReference = false
            val selected = clipEntry?.clipData?.let { data ->
                (0 until data.itemCount).mapNotNull { data.getItemAt(it).text?.toString() }.joinToString("\n")
            }
            if (!selected.isNullOrBlank()) reference(selected)
        } else delegate.setClipEntry(clipEntry)
    }
}

@Composable
private fun MessageSelectionMenu(
    toolbar: MessageSelectionToolbar,
    rootToWindow: Offset,
    canReference: Boolean,
    exportLabel: String,
    onExport: (() -> Unit)?,
    exportEnabled: Boolean,
    onReference: (() -> Unit) -> Unit,
    beforeCopy: () -> Unit,
) {
    val reducedMotion = LocalReduceGlassMotion.current
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = toolbar.actions != null
    var lastActions by remember { mutableStateOf(toolbar.actions) }
    SideEffect { toolbar.actions?.let { lastActions = it } }
    val shown = toolbar.actions ?: lastActions ?: return
    if (!visibility.currentState && !visibility.targetState) return
    val density = LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.let {
        IntRect(it.getLeft(density, layoutDirection), it.getTop(density),
            it.getRight(density, layoutDirection), it.getBottom(density))
    }
    val spacing = with(density) { 8.dp.roundToPx() }
    val position = remember(shown.rect, rootToWindow, safeInsets, spacing) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val selected = shown.rect.translate(rootToWindow)
                val safe = glassMenuSafeBounds(windowSize, safeInsets, spacing)
                val x = (selected.center.x - popupContentSize.width / 2f).roundToInt()
                    .coerceIn(safe.left, (safe.right - popupContentSize.width).coerceAtLeast(safe.left))
                val above = selected.top.roundToInt() - popupContentSize.height - spacing
                val y = (if (above >= safe.top) above else selected.bottom.roundToInt() + spacing)
                    .coerceIn(safe.top, (safe.bottom - popupContentSize.height).coerceAtLeast(safe.top))
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = toolbar::hide,
        properties = PopupProperties(focusable = false)) {
        AnimatedVisibility(visibleState = visibility,
            enter = fadeIn(GlassMotion.scalar(GlassMotion.PressMillis, reducedMotion)) +
                scaleIn(initialScale = .94f, animationSpec = GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion)),
            exit = fadeOut(GlassMotion.scalar(GlassMotion.PressMillis, reducedMotion)) +
                scaleOut(targetScale = .96f, animationSpec = GlassMotion.scalar(GlassMotion.MorphMillis, reducedMotion))) {
            GlassSurface(cornerRadius = 22.dp, materialProfile = GlassMaterialProfile.FloatingMenu,
                tint = MaterialTheme.colorScheme.surface.copy(alpha = .26f)) {
                Column(Modifier.widthIn(min = 184.dp, max = 280.dp).padding(6.dp)) {
                    SelectionMenuAction("复制", "document", shown.copy != null) {
                        beforeCopy()
                        shown.copy?.invoke()
                        toolbar.hide()
                    }
                    if (canReference) SelectionMenuAction("引用到消息输入框", "chat", shown.copy != null) {
                        shown.copy?.let(onReference)
                        toolbar.hide()
                    }
                    shown.selectAll?.let { selectAll ->
                        SelectionMenuAction("全选", "check", true, selectAll)
                    }
                    if (onExport != null) SelectionMenuAction(exportLabel, "download", exportEnabled) {
                        toolbar.hide()
                        onExport()
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionMenuAction(label: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .applyButtonDeformation(enabled = enabled, consumeDrag = false, drawShadow = false)
        .clip(RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val color = palette.onSurface.copy(alpha = if (enabled) 1f else .38f)
        FishGlyph(glyph, Modifier.size(18.dp), color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
