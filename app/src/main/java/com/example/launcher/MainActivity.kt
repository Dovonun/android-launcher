package com.example.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val H_PAD = 16
const val H_PAD2 = 2 * H_PAD

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
                val snackbarHostState = remember { SnackbarHostState() }

                val menu by viewVM.menu.collectAsState()
                when (val state = menu) {
                    is MenuState.Popup -> ShortcutPopup(state, appsVM, viewVM, snackbarHostState)
                    is MenuState.Sheet -> ContextSheet(
                        state, appsVM
                    ) { viewVM.setMenu(MenuState.None) }

                    is MenuState.None -> Unit
                }
                val allApps by appsVM.uiAllGrouped.collectAsState()
                val favorites by appsVM.favorites.collectAsState()
                val view by viewVM.view.collectAsState()
                Scaffold(
                    containerColor = Color.Transparent,
                    contentColor = Color.Transparent,
                    snackbarHost = {
                        SnackbarHost(snackbarHostState) { data ->
                            CustomLauncherSnackbar(data)
                        }
                    },
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
                    ) {
                        when (view) {
                            is View.Favorites -> Column(
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(start = H_PAD2.dp)
                                    .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onVerticalDrag = { change, dragAmount ->
                                                if (change.isConsumed) return@detectVerticalDragGestures
                                                if (dragAmount > 60f) systemVM.expandNotificationShade()
                                            })
                                    }) {
                                favorites.forEach { fav ->
                                    IconRow(
                                        fav,
                                        appsVM,
                                        viewVM,
                                        snackbarHostState
                                    )
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
                                    // TODO: What happens when 2 apps have the same name?
                                    items(items = list, key = { "all-${it.label}" }) { row ->
                                        IconRow(row, appsVM, viewVM, snackbarHostState)
                                    }
                                    item { Spacer(modifier = Modifier.height(48.dp)) }
                                }
                            }
                        }
                        LetterBar(
                            allApps,
                            viewVM,
                            listState,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
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
    val letters by remember(content) { derivedStateOf { content.keys.toList() } }
    val scrollIndexes by remember(content) {
        derivedStateOf {
            buildList {
                content.values.fold(0) { acc, list ->
                    add(acc)
                    acc + list.size + 2 // +1 for the header +1 for the space - Last index not used
                }
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val (height, botOffset, letterSizeDp) = remember(density, letters, screenHeight) {
        val slotFillFraction = 0.85f // fraction of slot to try to fill (0.65..0.85)
        val botOffset = (1f / 8f) * screenHeight
        val barHeight = screenHeight - (1f / 3f) * screenHeight - botOffset
        val letterSizeInDp = (barHeight / letters.size * slotFillFraction).coerceAtMost(48.dp)
        Triple(barHeight, botOffset, letterSizeInDp)
    }
    var isTouched by remember { mutableStateOf(false) }
    var letterIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scrollIndexes) {
        snapshotFlow { letterIndex }.distinctUntilChanged().collect { idx ->
            idx?.let {
                scrollIndexes.getOrNull(idx)?.let { target ->
                    listState.scrollToItem(target)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(bottom = botOffset)
            .height(height)
            .width(if (isTouched) (2.5 * H_PAD2).dp else 1.5 * H_PAD2.dp) // Assuming H_PAD2 is defined elsewhere
            .pointerInput(letters, scrollIndexes) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    viewVM.setView(View.AllApps)
                    letterIndex = ((down.position.y / size.height) * letters.size).toInt()
                    drag(down.id) { change ->
                        isTouched = true
                        change.consume()
                        val idx = ((change.position.y / size.height) * letters.size).toInt()
                        if (idx in letters.indices) {
                            letterIndex = idx
                            viewVM.setView(View.AllApps)
                        } else {
                            letterIndex = null
                            viewVM.setView(View.Favorites)
                        }
                    }
                    isTouched = false
                }
            }) {
        if (view is View.AllApps) {
            letters.forEachIndexed { i, letter ->
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
                        color = if (i == letterIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
fun IconRow(
    uiRow: UiRow,
    appVM: AppsVM,
    viewVM: ViewVM,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var fired by remember { mutableStateOf(false) }
    var layoutCoordinates: LayoutCoordinates? = null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(H_PAD.dp),
        modifier = modifier
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
                detectHorizontalDragGestures(
                    onDragStart = { fired = false }
                ) { change, drag ->
                    if (fired) return@detectHorizontalDragGestures
                    if (drag > 50f) {
                        layoutCoordinates?.boundsInWindow()?.bottom?.let { n ->
                            fired = true
                            change.consume()
                            scope.launch {
                                val entries = appVM.popupEntries(uiRow.item)
                                if (entries.isEmpty()) snackbarHostState.showSnackbar(
                                    "Nothing to show for this item",
                                    duration = SnackbarDuration.Short
                                ) else viewVM.setMenu(MenuState.Popup(entries, n))
                            }
                        } ?: return@detectHorizontalDragGestures
                    }
                }
            }) {
        RowIcon(uiRow.icon)
        RowLabel(uiRow.label)
    }
}

@Composable
fun RowIcon(icon: ImageBitmap?) = if (icon != null) Image(
    bitmap = icon, modifier = Modifier.size(40.dp), contentDescription = null
) else {
    Spacer(modifier = Modifier.size(40.dp))
}

@Composable
fun RowLabel(text: String) = Text(
    text = text,
    color = MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.labelLarge.copy(
        shadow = Shadow(
            color = MaterialTheme.colorScheme.surface, offset = Offset(0f, 0f), blurRadius = 8f
        )
    ),
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
fun CustomLauncherSnackbar(snackbarData: SnackbarData) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp), // Extra "floating" gap from the bottom
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .graphicsLayer {
                    shadowElevation = 12f
                    shape = RoundedCornerShape(28.dp)
                    clip = true
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            shape = RoundedCornerShape(28.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 12.dp), // More breathing room
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
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
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

@Composable
fun ShortcutPopup(
    state: MenuState.Popup,
    appsVM: AppsVM,
    viewVM: ViewVM,
    snackbarHostState: SnackbarHostState
) {
    val haptic = LocalHapticFeedback.current
    val entries = state.entries
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
            val maxVisible = 5
            val rowHeight = 58.dp
            val maxHeight = rowHeight * maxVisible - rowHeight / 3
            // NOTE: icon is 42dp and padding is 2 * 8dp
            val height = if (entries.size >= maxVisible) maxHeight else 58.dp * entries.size
            val listState = rememberLazyListState()
            if (entries.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .heightIn(max = maxHeight)
                        .offset(x = H_PAD.dp, y = (yDp - height - safeTopDp).coerceAtLeast(0.dp))
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large
                        )
                        .widthIn(max = maxWidth)
                        .padding(horizontal = H_PAD.dp, vertical = 12.dp)
                        .fadingEdges()
                ) {
                    items(entries) { item ->
                        IconRow(item, appsVM, viewVM, snackbarHostState)
                    }
                }
            } else {
                val text = "No shortcuts found for this App"
                Text(
                    text = text, fontSize = 17.sp, color = Color.Gray, fontStyle = FontStyle.Italic
                )
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