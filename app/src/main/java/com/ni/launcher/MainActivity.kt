package com.ni.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

const val H_PAD = 16
const val H_PAD2 = 2 * H_PAD
const val ROW_HEIGHT = 56
const val POPUP_V_PAD = 12

/** Pulls down the notification shade (hidden StatusBarManager API, so reflection). */
fun expandNotificationShade(context: Context) {
    runCatching {
        val service = context.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
    }.onFailure { Log.w("MainActivity", "Could not expand notification shade", it) }
}

class MainActivity : ComponentActivity() {
    private val viewVM: ViewVM by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ReturnFromAwaitPointerEventScope", "UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { viewVM.back() }
        enableEdgeToEdge()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContent {
            LauncherTheme {
                val appsVM: AppsVM = viewModel()
                val listState = rememberLazyListState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    appsVM.toast.collect { message ->
                        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                    }
                }

                val menu by viewVM.menu.collectAsState()
                when (val state = menu) {
                    is MenuState.Popup -> ShortcutPopup(state, appsVM, viewVM, snackbarHostState)
                    is MenuState.Sheet -> ContextSheet(
                        state, appsVM, viewVM
                    ) { viewVM.setMenu(MenuState.None) }

                    is MenuState.None -> Unit
                }
                val currentView by viewVM.view.collectAsState()
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0),
                    snackbarHost = {
                        SnackbarHost(snackbarHostState) { data ->
                            CustomLauncherSnackbar(data)
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
                    ) {
                        when (val v = currentView) {
                            is View.TagManager -> TagManagerScreen(appsVM = appsVM, viewVM = viewVM)
                            is View.ManageTag -> ManageTagScreen(tag = v.tag, items = v.items, appsVM = appsVM, viewVM = viewVM)
                            is View.ManageTagAdd -> ManageTagAddScreen(tag = v.tag, items = v.items, appsVM = appsVM, viewVM = viewVM)
                            is View.Favorites, is View.AllApps -> {
                                val allApps by appsVM.uiAllGrouped.collectAsState()
                                when (v) {
                                    is View.Favorites -> {
                                        val favorites by appsVM.favorites.collectAsState()
                                        val favTag by appsVM.getTag(TAG.FAV).collectAsState(null)
                                        // Favorites: plain Column, index 0 at the bottom. The count is not capped and the list does not scroll.
                                        Column(
                                            verticalArrangement = Arrangement.Bottom,
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .padding(start = H_PAD2.dp)
                                                .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
                                                .pointerInput(Unit) {
                                                    detectVerticalDragGestures(
                                                        onVerticalDrag = { change, dragAmount ->
                                                            if (change.isConsumed) return@detectVerticalDragGestures
                                                            if (dragAmount > 60f) expandNotificationShade(this@MainActivity)
                                                        })
                                                }) {
                                            favorites.asReversed().forEach { fav ->
                                                IconRow(
                                                    fav, appsVM, viewVM, snackbarHostState, parent = favTag
                                                )
                                            }
                                        }
                                    }

                                    is View.AllApps -> LazyColumn(
                                        modifier = Modifier.padding(start = H_PAD2.dp),
                                        state = listState,
                                        contentPadding = PaddingValues(
                                            top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp,
                                            bottom = 2f / 3f * LocalConfiguration.current.screenHeightDp.dp
                                        )
                                    ) {
                                        allApps.forEach { (letter, list) ->
                                            item {
                                                Box(
                                                    modifier = Modifier
                                                        .width(40.dp)
                                                        .height(40.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = letter.toString(),
                                                        style = MaterialTheme.typography.headlineSmall.copy(
                                                            shadow = Shadow(
                                                                color = MaterialTheme.colorScheme.surface.copy(
                                                                    alpha = 0.7f
                                                                ), offset = Offset(0f, 0f), blurRadius = 8f
                                                            ),
                                                            fontWeight = MaterialTheme.typography.headlineSmall.fontWeight,
                                                        ),
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        textAlign = TextAlign.Center
                                                    )

                                                }
                                            }
                                            itemsIndexed(
                                                items = list,
                                                key = { index, item -> "$letter-${item.label}-$index" }
                                            ) { index, it ->
                                                IconRow(it, appsVM, viewVM, snackbarHostState)
                                            }
                                            item { Spacer(modifier = Modifier.height(48.dp)) }
                                        }
                                    }

                                    else -> Unit
                                }
                                LetterBar(
                                    letters = remember(allApps) { allApps.keys.toList() },
                                    // header + spacer per letter
                                    scrollIndexes = remember(allApps) { allApps.values.runningFold(0) { acc, list -> acc + list.size + 2 } },
                                    listState = listState,
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                    showLetters = v is View.AllApps,
                                    shadow = true,
                                ) { onLetter -> viewVM.setView(if (onLetter == false) View.Favorites else View.AllApps) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewVM.softReset()
    }

    override fun onStop() {
        super.onStop()
        // Persist pending graph edits when the launcher leaves the foreground.
        (application as NiLauncher).flushGraphAsync()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

}

/**
 * Right-edge letter bar (All Apps and the Add-items picker): touching letter i scrolls [listState]
 * to [scrollIndexes][i]. [onTouch] gets null on touch down, then whether the finger is on a letter.
 */
@Composable
fun LetterBar(
    letters: List<Char>,
    scrollIndexes: List<Int>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    showLetters: Boolean = true,
    shadow: Boolean = false,
    onTouch: (onLetter: Boolean?) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val (height, botOffset, letterSizeDp) = remember(letters, screenHeight) {
        val slotFillFraction = 0.85f // fraction of slot to try to fill (0.65..0.85)
        val botOffset = (1f / 8f) * screenHeight
        val barHeight = screenHeight - (1f / 3f) * screenHeight - botOffset
        val letterSizeInDp = (barHeight / letters.size * slotFillFraction).coerceAtMost(48.dp)
        Triple(barHeight, botOffset, letterSizeInDp)
    }
    var isTouched by remember { mutableStateOf(false) }
    var letterIndex by remember { mutableStateOf<Int?>(null) }
    val animatedWidth by animateDpAsState(
        targetValue = if (isTouched) (2.5 * H_PAD2).dp else (1.5 * H_PAD2).dp,
        animationSpec = tween(durationMillis = 120),
        label = "LetterBarWidth"
    )
    val animatedShift by animateDpAsState(
        targetValue = if (isTouched) 0.dp else 24.dp,
        animationSpec = tween(durationMillis = 120),
        label = "LetterBarShift"
    )
    val letterOffset = animatedShift - 12.dp // right inset
    fun selectLetter(idx: Int?) {
        if (idx == letterIndex) return
        letterIndex = idx
        idx?.let {
            scrollIndexes.getOrNull(it)?.let { target ->
                listState.requestScrollToItem(target)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(bottom = botOffset)
            .height(height)
            .width(animatedWidth)
            .pointerInput(letters, scrollIndexes) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onTouch(null)
                    selectLetter(((down.position.y / size.height) * letters.size).toInt())
                    drag(down.id) { change ->
                        isTouched = true
                        change.consume()
                        val idx = ((change.position.y / size.height) * letters.size).toInt()
                        val onLetter = idx in letters.indices
                        selectLetter(if (onLetter) idx else null)
                        onTouch(onLetter)
                    }
                    isTouched = false
                }
            }) {
        if (showLetters) {
            letters.forEachIndexed { i, letter ->
                Box(
                    modifier = Modifier
                        .offset(x = letterOffset)
                        .width(letterSizeDp)
                        .height(letterSizeDp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter.toString(),
                        style = TextStyle(
                            fontSize = with(density) { letterSizeDp.toSp() },
                            shadow = if (shadow) Shadow(MaterialTheme.colorScheme.surface, Offset(0f, 0f), 4f) else null,
                        ),
                        color = if (i == letterIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
fun IconRow(
    item: LauncherItem,
    appVM: AppsVM,
    viewVM: ViewVM,
    snackbarHostState: SnackbarHostState,
    parent: LauncherItem.Tag? = null,
    anchorBottomY: Float? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    LauncherRowLayout(
        item = item,
        modifier = modifier
            .pointerInput(item, parent) {
                detectTapGestures(onTap = {
                    appVM.launch(item)
                    viewVM.leave()
                }, onLongPress = { viewVM.setMenu(MenuState.Sheet(item, parent)) })
            }
            .onSwipeRight(item, parent) { n ->
                scope.launch {
                    val entries = appVM.popupEntriesSnapshot(item)
                    if (entries.isEmpty()) snackbarHostState.showSnackbar("Nothing to show for this item", duration = SnackbarDuration.Short)
                    // Items inside the popup get 'item' (if it's a Tag) as their parent.
                    else viewVM.setMenu(MenuState.Popup(entries, n, item as? LauncherItem.Tag, anchorBottomY = anchorBottomY ?: n))
                }
            }
    )
}

@Composable
fun RowIcon(icon: ImageBitmap?, size: androidx.compose.ui.unit.Dp = 40.dp) =
    if (icon == null) Spacer(modifier = Modifier.size(size))
    else Image(bitmap = icon, modifier = Modifier.size(size), contentDescription = null)

@Composable
fun RowLabel(text: String, overflow: TextOverflow = TextOverflow.Ellipsis) = Text(
    text = text,
    color = MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.labelLarge.copy(
        shadow = Shadow(
            color = MaterialTheme.colorScheme.surface, offset = Offset(0f, 0f), blurRadius = 8f
        )
    ),
    maxLines = 1,
    overflow = overflow,
)

@Composable
fun LauncherRowLayout(item: LauncherItem, modifier: Modifier = Modifier, label: String = item.label, overflow: TextOverflow = TextOverflow.Ellipsis) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        RowIcon(item.icon)
        RowLabel(label, overflow)
    }
}

@Composable
fun SheetEntry(text: String, onClick: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                onDismiss()
                onClick()
            })
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(42.dp) // same as AppRow height
    ) {
        Spacer(modifier = Modifier.width(74.dp)) // for icon space (42 + 32 spacing)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun CustomLauncherSnackbar(snackbarData: SnackbarData) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp), // Extra "floating" gap from the bottom
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                shadowElevation = 12f
                shape = RoundedCornerShape(28.dp)
                clip = true
            },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            shape = RoundedCornerShape(28.dp)
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = 24.dp, vertical = 12.dp
                ), // More breathing room
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = snackbarData.visuals.message,
                    // Decreased size: labelMedium is smaller than labelLarge
                    style = MaterialTheme.typography.labelMedium.copy(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.3f),
                            offset = Offset(0f, 4f),
                            blurRadius = 10f
                        )
                    ), color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** Right swipe (> 50 px) on this element: [onSwipe] once per drag, with the element's bottom in window px. */
fun Modifier.onSwipeRight(vararg keys: Any?, enabled: Boolean = true, onSwipe: (bottomY: Float) -> Unit) = composed {
    var layoutCoordinates: LayoutCoordinates? = null
    var fired by remember { mutableStateOf(false) }
    onGloballyPositioned { layoutCoordinates = it }.pointerInput(*keys) {
        detectHorizontalDragGestures(onDragStart = { fired = false }) { change, drag ->
            if (!enabled || fired || drag <= 50f) return@detectHorizontalDragGestures
            layoutCoordinates?.boundsInWindow()?.bottom?.let { y ->
                fired = true
                change.consume()
                onSwipe(y)
            }
        }
    }
}

fun Modifier.fadingEdges(fadeHeightPx: Float = 24f) = composed {
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithCache {
            onDrawWithContent {
                drawContent()
                val fraction = fadeHeightPx / size.height
                val topStops = listOf(
                    0.00f to Color.Transparent,
                    0.25f * fraction to Color.Transparent.copy(alpha = 0.10f), // slow start
                    0.50f * fraction to Color.Transparent.copy(alpha = 0.35f), // accelerating
                    0.75f * fraction to Color.Transparent.copy(alpha = 0.70f), // near full
                    1.00f * fraction to Color.Black                                                       // inward: always full keep
                )
                drawRect(
                    brush = Brush.verticalGradient(*topStops.toTypedArray()),
                    blendMode = BlendMode.DstIn
                )
                val bottomStops = listOf(
                    (1f - fraction) to Color.Black,                                                        // inward: full keep
                    (1f - fraction) + 0.25f * fraction to Color.Transparent.copy(alpha = 0.70f),
                    (1f - fraction) + 0.50f * fraction to Color.Transparent.copy(alpha = 0.35f),
                    (1f - fraction) + 0.75f * fraction to Color.Transparent.copy(alpha = 0.10f),
                    1f to Color.Transparent
                )
                drawRect(
                    brush = Brush.verticalGradient(*bottomStops.toTypedArray()),
                    blendMode = BlendMode.DstIn
                )
            }
        }
}

/**
 * Popup shell shared by the swipe popups: a full-screen tap-to-dismiss layer and a [panel]
 * modifier that places up to 5 rows (more scroll) right above [anchorY] (window px).
 */
@Composable
fun AnchoredPopup(anchorY: Float, rows: Int, onDismiss: () -> Unit, content: @Composable (panel: Modifier) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val anchorDp = with(LocalDensity.current) { anchorY.toDp() }
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp - H_PAD2.dp
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    Popup(properties = PopupProperties(focusable = true), onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
        ) {
            val maxVisible = 5
            val rowHeight = ROW_HEIGHT.dp // icon 40dp + 2 * 8dp padding
            val pad = POPUP_V_PAD.dp
            val maxHeight = rowHeight * maxVisible - rowHeight / 3
            val isScrollable = rows >= maxVisible
            val contentHeight = if (isScrollable) maxHeight else rowHeight * rows
            val offsetHeight = if (isScrollable) contentHeight - pad else contentHeight + pad
            content(
                Modifier
                    .heightIn(max = maxHeight)
                    .offset(x = H_PAD.dp, y = (anchorDp - offsetHeight - safeTopDp).coerceAtLeast(0.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large)
                    .widthIn(max = maxWidth)
                    .padding(horizontal = H_PAD.dp, vertical = pad)
                    .fadingEdges()
            )
        }
    }
}

@Composable
fun ShortcutPopup(
    state: MenuState.Popup, appsVM: AppsVM, viewVM: ViewVM, snackbarHostState: SnackbarHostState
) {
    val entries = state.entries
    AnchoredPopup(state.anchorBottomY, entries.size, onDismiss = { viewVM.setMenu(MenuState.None) }) { panel ->
        // Popups are short: a plain Column. Entries are laid out reversed so index 0 sits at the
        // bottom next to the anchor; reverseScrolling starts the scroll at the bottom.
        if (entries.isNotEmpty()) Column(modifier = panel.verticalScroll(rememberScrollState(), reverseScrolling = true)) {
            entries.asReversed().forEach { item ->
                IconRow(item, appsVM, viewVM, snackbarHostState, parent = state.parent, anchorBottomY = state.anchorBottomY)
            }
        } else Text(text = "No shortcuts found for this App", fontSize = 17.sp, color = Color.Gray, fontStyle = FontStyle.Italic)
    }
}

@OptIn(ExperimentalMaterial3Api::class)

@Composable

fun ContextSheet(state: MenuState.Sheet, appsVM: AppsVM, viewVM: ViewVM, reset: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries by produceState(initialValue = emptyList<SheetAction>(), state.item, state.parent) {
        value = appsVM.sheetActionsSnapshot(state.item, state.parent) { viewVM.setView(it) }
    }
    val badges by appsVM.getTagsForItem(state.item).collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    ModalBottomSheet(
        onDismissRequest = reset,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = H_PAD.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 24.dp)
            ) {
                val title = if (state.item is LauncherItem.Tag) state.item.name else state.item.label
                if (state.item !is LauncherItem.Tag) RowIcon(state.item.icon, size = 32.dp)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = H_PAD.dp)
                        .weight(1f)
                )
                if (badges.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        badges.forEach { tag ->
                            SuggestionChip(
                                onClick = {
                                    reset()
                                    viewVM.setView(View.TagManager)
                                },
                                label = { Text(tag, fontSize = 10.sp) },
                                shape = RoundedCornerShape(8.dp),
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                border = null,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            entries.forEach { entry ->
                SheetEntry(entry.label, entry.onTap, reset)
            }
        }
    }
}
