package com.example.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val H_PAD = 16
const val H_PAD2 = 2 * H_PAD

// TODO: Next AI chat about themes | Material3 in compose
// TODO: Next Make it look nice. Can you do the outline thing on text?
// TODO: fix alignment of popup and other stuff that was hardcoded to 42px
class MainActivity : ComponentActivity() {
    private val viewVM: ViewVM by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ReturnFromAwaitPointerEventScope")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        setContent {
            LauncherTheme {
                val appsVM: AppsVM = viewModel()
                val systemVM: SystemVM = viewModel()
                val listState = rememberLazyListState()

                val menu by viewVM.menu.collectAsState()
                when (val state = menu) {
                    is MenuState.Popup -> ShortcutPopup(state, appsVM, viewVM)
                    is MenuState.Sheet -> ContextSheet(
                        state, appsVM
                    ) { viewVM.setMenu(MenuState.None) }

                    is MenuState.None -> Unit
                }
                val allApps by appsVM.uiAllGrouped.collectAsState()
                val favorites by appsVM.favorites.collectAsState()
                val view by viewVM.view.collectAsState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
                ) {
                    when (view) {
                        is View.Favorites -> LazyColumn {
                            Log.d("MainActivity", "recompose")
                            for (i in 1..100) {
                                item {
                                    Text("Item $i", color = Color.White)
                                }
                            }
                        }
//                        is View.Favorites -> Column(
//                            verticalArrangement = Arrangement.Bottom,
//                            modifier = Modifier
//                                .fillMaxHeight()
//                                .padding(start = H_PAD2.dp)
//                                .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
//                                .pointerInput(Unit) {
//                                    detectVerticalDragGestures(
//                                        onVerticalDrag = { change, dragAmount ->
//                                            if (change.isConsumed) return@detectVerticalDragGestures
//                                            if (dragAmount > 60f) systemVM.expandNotificationShade()
//                                        })
//                                }) {
//                            favorites.forEach { fav -> IconRow(fav, appsVM, viewVM, false) }
//                        }

                        is View.AllApps -> LazyColumn(
                            modifier = Modifier.padding(start = H_PAD2.dp),
                            state = listState,
                            contentPadding = PaddingValues(
                                top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp,
                                bottom = 2f / 3f * LocalConfiguration.current.screenHeightDp.dp
                            )
                        ) {
                            Log.d("MainActivity", "recompose")
                            appsVM.uiAllGrouped.value.forEach { (letter, list) ->
                                item {
                                    Box(
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = letter.toString(),
//                                            style = MaterialTheme.typography.headlineSmall.copy(
//                                                shadow = Shadow(
//                                                    color = MaterialTheme.colorScheme.surface.copy(
//                                                        alpha = 0.7f
//                                                    ), offset = Offset(0f, 0f), blurRadius = 8f
//                                                ),
//                                                fontWeight = MaterialTheme.typography.headlineSmall.fontWeight,
//                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )

                                    }
                                }
                                // TODO: What happens when 2 apps have the same name?
                                items(items = list, key = { "all-${it.label}" }) { row ->
                                    IconRow(row, appsVM, viewVM, true)
                                }
//                                item { Spacer(modifier = Modifier.height(48.dp)) }
                            }
                        }
                    }
                    LetterBar(
                        allApps, viewVM, listState, modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        viewVM.softReset()
    }
}

@Composable
fun LetterBar(
    content: Map<Char, List<UiRow>>,
    viewVM: ViewVM,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val view by viewVM.view.collectAsState()
    val scope = rememberCoroutineScope()

    val letters by remember(content) { derivedStateOf { content.keys.toList() } }
    val scrollIndexes by remember(content) {
        derivedStateOf {
            buildList {
                content.values.fold(0) { acc, list ->
                    add(acc)
                    acc + list.size + 2 // +1 for the header +1 for the spacer
                }
            }
        }
    }
    val letterToIndex = remember(content) {
        val map = mutableMapOf<Char, Int>()
        var idx = 0
        content.forEach { (letter, apps) ->
            map[letter] = idx   // index of the header item
            idx += 1            // header
            idx += apps.size    // apps
            idx += 1            // spacer
        }
        map
    }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val (height, botOffset, letterSizeDp) = remember(density, letters, screenHeight) {
        val slotFillFraction = 0.85f; // fraction of slot to try to fill (0.65..0.85)
        val botOffset = 1f / 8f * screenHeight
        val barHeight = screenHeight - 1f / 3f * screenHeight - botOffset
        val letterSizeInDp = (barHeight / letters.size * slotFillFraction).coerceAtMost(48.dp)
        Triple(barHeight, botOffset, letterSizeInDp)
    }
    var rowHeight = remember(density) { with(density) { 42.dp.toPx() } }
    var isTouched by remember { mutableStateOf(false) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var lastIdx by remember { mutableIntStateOf(0) }

//    fun scroll(idx: Int) {
//        scrollJob?.cancel()
//        scrollJob = scope.launch {
//            listState.scrollToItem(idx)
//        }
//    }

    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(bottom = botOffset)
            .height(height)
            .width(if (isTouched) (2.5 * H_PAD2).dp else H_PAD2.dp)
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isTouched = true
                    viewVM.setView(View.AllApps)
//                    val initialIdx = (down.position.y / size.height * letters.size).toInt()
//                    lastIdx = scrollIndexes.getOrNull(initialIdx) ?: 0
//                    scrollJob = scope.launch { listState.scrollToItem(lastIdx) }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    drag(down.id) { change ->
                        val idx = ((change.position.y / size.height) * letters.size).toInt()
                        val scrollIdx = scrollIndexes.getOrNull(idx) ?: 0
                        if (lastIdx != scrollIdx) {
                            val progress = change.position.y
                            val totalContentHeightPx = listState.layoutInfo.totalItemsCount * rowHeight
                            val targetOffset = progress * totalContentHeightPx - rowHeight/2
                            Log.d("MainActivity", "targetOffset: $targetOffset")
                            Log.d("MainActivity", "total Height: $totalContentHeightPx")
//                            scope.launch { listState.scroll { scrollBy(targetOffset - listState.firstVisibleItemScrollOffset) } }
//                            val fraction = change.position.y / size.height
//                            val letterIndex = (fraction * letters.size).toInt().coerceIn(0, letters.size - 1)
//                            val targetIndex = letterToIndex[letters[letterIndex]] ?: 0
                            scope.launch {
                                listState.scrollToItem(scrollIdx)   // this is smooth and instant-feel
                            }
//                            scrollJob?.cancel()
//                            scrollJob = scope.launch { listState.requestScrollToItem(scrollIdx) }
//                            scroll(scrollIdx)
                            lastIdx = scrollIdx
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    isTouched = false
                }
            }
    )
    {
        if (view is View.AllApps) {
            letters.forEach { letter ->
                Box(
                    modifier = Modifier
                        .width(letterSizeDp)
                        .height(letterSizeDp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = letter.toString(),
                        style = TextStyle(
                            fontSize = with(density) { letterSizeDp.toSp() }, shadow = Shadow(
                                color = MaterialTheme.colorScheme.surface,
                                offset = Offset(0f, 0f),
                                blurRadius = 4f
                            )
                        ),
//                        color = if (letter == selectedLetter) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun IconRow(uiRow: UiRow, appVM: AppsVM, viewVM: ViewVM, fast: Boolean, modifier: Modifier = Modifier) {
    var layoutCoordinates: LayoutCoordinates? = null
    val modifier = modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .onGloballyPositioned { coordinates -> layoutCoordinates = coordinates }
        .pointerInput(Unit) {
            detectTapGestures(onTap = {
                appVM.launch(uiRow.item)
                viewVM.leave()
            }, onLongPress = { viewVM.setMenu(MenuState.Sheet(uiRow.item)) })
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures { change, drag ->
                if (change.isConsumed) return@detectHorizontalDragGestures
                if (drag > 50f) {
                    layoutCoordinates?.boundsInWindow()?.bottom?.let { n ->
                        change.consume()
                        viewVM.setMenu(MenuState.Popup(uiRow.item, n))
                    } ?: return@detectHorizontalDragGestures
                }
            }
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
        modifier = modifier) {
//        RowIcon(uiRow.icon)
//        Image(uiRow.iconPainter, modifier = Modifier.size(40.dp), contentDescription = null)
        RowLabel(uiRow.label)
    }
}

@Composable
fun RowIcon(icon: ImageBitmap) =
    Image(bitmap = icon, modifier = Modifier.size(40.dp), contentDescription = null)

@Composable
fun RowLabel(text: String) = Text(
    text = text,
    color = MaterialTheme.colorScheme.onSurface,
//    style = MaterialTheme.typography.labelLarge.copy(
//        shadow = Shadow(
//            color = MaterialTheme.colorScheme.surface, offset = Offset(0f, 0f), blurRadius = 8f
//        )
//    ),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)

@Composable
fun SheetEntry(text: String, onClick: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                onClick()
                onDismiss()
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
fun ShortcutPopup(state: MenuState.Popup, appsVM: AppsVM, viewVM: ViewVM) {
    val haptic = LocalHapticFeedback.current
    val entries by produceState(initialValue = emptyList(), state.item) {
        value = appsVM.popupEntries(state.item)
    }
    val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val yDp = with(LocalDensity.current) { state.yPos.toDp() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = screenWidth - H_PAD2.dp
    val reset = { viewVM.setMenu(MenuState.None) }
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    Popup(properties = PopupProperties(focusable = true), onDismissRequest = reset) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null, onClick = reset)
        ) {
            val height = 58.dp * entries.size // icon is 42dp and padding is 2 * 8dp
            Column(
                modifier = Modifier
                    .offset(
                        x = H_PAD.dp, y = (yDp - height - safeTopDp).coerceAtLeast(0.dp)
                    )
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large
                    )
                    .widthIn(max = maxWidth)
                    .padding(horizontal = H_PAD.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                if (entries.isNotEmpty()) {
                    entries.reversed().forEach { item -> IconRow(item, appsVM, viewVM, false) }
                } else {
                    val text = "No shortcuts found for this App"
                    Text(
                        text = text,
                        fontSize = 17.sp,
                        color = Color.Gray,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(state: MenuState.Sheet, appsVM: AppsVM, reset: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries by produceState(initialValue = emptyList(), state.item) {
        value = appsVM.sheetEntries(state.item)
    }
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    ModalBottomSheet(
        onDismissRequest = reset,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets(0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            entries.forEach { entry -> SheetEntry(entry.label, entry.onTap, reset) }
            Spacer(Modifier.height(42.dp))
        }
    }
}