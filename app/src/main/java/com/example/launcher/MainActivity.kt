package com.example.launcher

import android.R.color.transparent
import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.WindowInsets as ViewWindowInsets

const val H_PAD = 24
const val H_PAD2 = 2 * H_PAD

private fun buildBarIndex(apps: Map<Char, List<UiRow>>): List<Int> = buildList {
    apps.values.fold(0) { acc, list ->
        add(acc)
        acc + list.size + 1 // +1 for the header
    }
}

class MainActivity : ComponentActivity() {
    private val viewVM: ViewVM by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ReturnFromAwaitPointerEventScope")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.attributes.setWallpaperTouchEventsEnabled(false)
        window.setBackgroundDrawableResource(transparent)
        setContent {
            window.insetsController?.hide(ViewWindowInsets.Type.statusBars())
            val context = LocalContext.current
            val haptic = LocalHapticFeedback.current
            val appsVM: AppsVM = viewModel()
            val systemVM: SystemVM = viewModel()
            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperColors: WallpaperColors? =
                remember { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }

            val primaryColorInt: Int? = remember { wallpaperColors?.primaryColor?.toArgb() }
            val primaryColor = remember { primaryColorInt?.let { Color(it) } ?: Color.Black }
            val listState = rememberLazyListState()
//            val primaryColorHsv = remember { FloatArray(3) }
//            val bright_primaryColor = remember { Color.hsv(primaryColor.hue, 1f, 1f) }

            val view by viewVM.view.collectAsState()
            LaunchedEffect(view) {
                val v = view
                if (v is View.AllApps) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    listState.scrollToItem(v.index, 0)
                }
            }
            val menu by viewVM.menu.collectAsState()
            LaunchedEffect(menu) {
                when (menu) {
                    is MenuState.Popup -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    is MenuState.Sheet -> haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    is MenuState.None -> Unit
                }
            }
            when (val state = menu) {
                is MenuState.Popup -> ShortcutPopup(state, appsVM, viewVM)
                is MenuState.Sheet -> ContextSheet(state, appsVM) { viewVM.setMenu(MenuState.None) }
                is MenuState.None -> Unit
            }
            val allApps by appsVM.uiAllGrouped.collectAsState()
            val scrollIndexes by remember(allApps) { derivedStateOf { buildBarIndex(allApps) } }
            val favorites by appsVM.favorites.collectAsState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                        favorites.forEach { fav -> IconRow(fav, appsVM, viewVM) }
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
                                Text(
                                    text = letter.toString(),
                                    fontSize = 24.sp,
                                    color = primaryColor,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .padding(start = 64.dp)
                                )
                            }
                            // TODO: What happens when 2 apps have the same name?
                            items(items = list, key = { "all-${it.label}" }) { row ->
                                IconRow(row, appsVM, viewVM)
                            }
                        }
                    }
                }
                LetterBar(
                    color = primaryColor,
                    view = view,
                    letters = allApps.keys.toList(),
                    update = { index ->
                        val scrollI = scrollIndexes.elementAtOrNull(index)
                        viewVM.setView(if (scrollI == null) View.Favorites else View.AllApps(scrollI))
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (System.currentTimeMillis() - viewVM.leaveTime.value < 5000) viewVM.setLeaveTime(0L) else viewVM.setView(
            View.Favorites
        )
        viewVM.setMenu(MenuState.None)
    }
}

@Composable
fun LetterBar(
    color: Color, // TODO: can this handled via a theme?
    letters: List<Char>, view: View, update: (Int) -> Unit, modifier: Modifier = Modifier
) {
    var isScrollbarTouched by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp) // Start 1/3 from the top
            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp) // End 1/8 from the bottom
            .fillMaxHeight()
            .width(if (isScrollbarTouched) (2 * H_PAD2).dp else H_PAD2.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { isScrollbarTouched = true },
                    onDragEnd = { isScrollbarTouched = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val index = (change.position.y / size.height * letters.size).toInt()
                        selectedLetter = letters.getOrNull(index)
                        update(index)
                    })
            }) {
        if (view is View.AllApps) {
            letters.forEach { letter ->
                Text(
                    text = letter.toString(),
                    fontSize = if (letter == selectedLetter) 24.sp else 16.sp, // Make the selected letter bigger
                    color = if (letter == selectedLetter) Color.White else color,
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
fun IconRow(
    uiRow: UiRow,
    appVM: AppsVM,
    viewVM: ViewVM,
    modifier: Modifier = Modifier,
) {
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
                    viewVM.setLeaveTime(System.currentTimeMillis())
                    viewVM.setMenu(MenuState.None)
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
            }) {
        RowIcon(uiRow.icon)
        RowLabel(uiRow.label)
    }
}

@Composable
fun RowIcon(icon: ImageBitmap) =
    Image(bitmap = icon, modifier = Modifier.size(42.dp), contentDescription = null)

@Composable
fun RowLabel(text: String) = Text(
    text = text,
    fontSize = 24.sp,
    color = Color.White,
    style = MaterialTheme.typography.labelMedium.copy(
        shadow = Shadow(
            color = Color.Black, offset = Offset(0.01f, 0.01f), blurRadius = 5f
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
        Text(text = text, fontSize = 24.sp, color = Color.White)
    }
}

@Composable
fun ShortcutPopup(state: MenuState.Popup, appsVM: AppsVM, viewVM: ViewVM) {
    val entries by produceState(initialValue = emptyList(), state.item) {
        value = appsVM.popupEntries(state.item)
    }
    val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val yDp = with(LocalDensity.current) { state.yPos.toDp() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxWidth = screenWidth - H_PAD2.dp
    val reset = { viewVM.setMenu(MenuState.None) }
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
                    .background(Color(0xFF121212), RoundedCornerShape(12.dp))
                    .widthIn(max = maxWidth)
                    .padding(horizontal = H_PAD.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                if (entries.isNotEmpty()) {
                    entries.reversed().forEach { item -> IconRow(item, appsVM, viewVM) }
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries by produceState(initialValue = emptyList(), state.item) {
        value = appsVM.sheetEntries(state.item)
    }
    ModalBottomSheet(
        onDismissRequest = reset,
        sheetState = sheetState,
        containerColor = Color(0xFF121212),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        windowInsets = WindowInsets(0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            entries.forEach { entry -> SheetEntry(entry.label, entry.onTap, reset) }
            Spacer(Modifier.height(42.dp))
        }
    }
}