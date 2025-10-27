package com.example.launcher

import android.R
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.view.WindowInsets as ViewWindowInsets

sealed class RowAction {
    data object Launch : RowAction()
    data class ShowPopup(val yPos: Float) : RowAction()
    data object ShowSheet : RowAction()
}

sealed interface View {
    data object Favorites : View
    data class AllApps(val letter: Char) : View
}

sealed interface MenuState {
    data object None : MenuState
    data class ListPopup(
        val list: StateFlow<List<UiRow>>, val yPos: Float, val reset: () -> Unit
    ) : MenuState

    data class ContextSheet(val entries: StateFlow<List<SheetRow>>, val reset: () -> Unit) :
        MenuState
}

//sealed class ListItem {
//    data class Header(val letter: Char) : ListItem()
//    data class AppEntry(val label: String, val icon: ImageBitmap) : ListItem() // TODO: how do I know which entry was clicked? callback? index?
//}

private fun expandNotificationShade(context: Context) {
    try {
        val statusBarService = context.getSystemService("statusbar")
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val expandMethod = statusBarManager.getMethod("expandNotificationsPanel")
        expandMethod.invoke(statusBarService)
    } catch (e: Exception) {
        Log.e("MainActivity", "Failed to expand notifications: ${e.message}")
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
        window.setBackgroundDrawableResource(R.color.transparent)

        setContent {
            Text(text = "debug")
            window.insetsController?.hide(ViewWindowInsets.Type.statusBars())
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            val appsVM: AppsVM = viewModel()
            val haptic = LocalHapticFeedback.current

            val view by viewVM.view.collectAsState()
//            val appListData by appsVM.launchableListData.collectAsState()
//            val favorites by appsVM.favoriteApps.collectAsState()
//            val selectedApp by appsVM.selectedLaunchable.collectAsState()
//
//            val shortcuts by appsVM.shortcutUiItems.collectAsState()

            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperColors: WallpaperColors? =
                remember { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }


            val primaryColorInt: Int? = remember { wallpaperColors?.primaryColor?.toArgb() }
            val primaryColor = remember { primaryColorInt?.let { Color(it) } ?: Color.Black }
//            val primaryColorHsv = remember { FloatArray(3) }
//            val bright_primaryColor = remember { Color.hsv(primaryColor.hue, 1f, 1f) }
            val listState = rememberLazyListState()

//            var showSheetForLaunchable by remember { mutableStateOf<Launchable?>(null) }
            var letterBarBounds by remember { mutableStateOf(Rect.Zero) }

            // safeDrawing top as Dp, then px
            // TODO: Can't I cache this?
            val density = LocalDensity.current
            val safeTopDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            val safeTopPx = with(density) { safeTopDp.toPx() }

            var menuState by remember { mutableStateOf<MenuState>(MenuState.None) }
            val resetMenu = { menuState = MenuState.None }

            val favorites2 by appsVM.uiList(TAG.FAV).collectAsState()

            fun rowAction(uiRow: UiRow, action: RowAction) {
                when (action) {
                    is RowAction.Launch -> appsVM.launch(uiRow.item)
                    is RowAction.ShowPopup -> menuState = MenuState.ListPopup(
                        // TODO: How does it look like to pass a tag here?
                        // Is the tag just wrapping the uiRow? Who unwraps?
                        // launch function because unwarp is always index 0. Children are uiRows.
                        appsVM.popupEntires(uiRow.item), action.yPos - safeTopPx, resetMenu
                    )

                    is RowAction.ShowSheet -> menuState = MenuState.ContextSheet(
                        appsVM.getContextEntries(uiRow.item), resetMenu
                    )
                }
            }


//            fun selectAppWithBounds(launchable: Launchable, bounds: Rect) {
//                anchorBounds = Rect(
//                    left = bounds.left,
//                    top = bounds.top - safeTopPx,
//                    right = bounds.right,
//                    bottom = bounds.bottom - safeTopPx
//                )
//                appsVM.selectApp(launchable)
//            }
            val allApps by appsVM.uiAllGrouped.collectAsState()
            val allAppsBarIndex by remember(allApps) {
                derivedStateOf {
                    buildMap<Char, Int> {
                        var total = 0
                        allApps.forEach { (letter, list) ->
                            put(letter, total)
                            total += list.size + 1 // +1 for the header
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
//                    .border(1.dp, Color.White)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                if (dragAmount.y < 0) {
                                    viewVM.setView(View.Favorites)
                                    change.consume()
                                }
                            },
                        )
                    }) {

                // TODO: add the haptic back in
//                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
//                haptic.performHapticFeedback(HapticFeedbackType.LongPress) // for long menu
                LaunchedEffect(resetMenu) {} // ? // ?
                // TODO: Flicker in favorites when popup appears in emulator
                // no flicker in all apps list(lazycol?)
                when (val state = menuState) {
                    is MenuState.ListPopup -> ShortcutPopup(state, ::rowAction)
                    is MenuState.ContextSheet -> ContextSheet(state) // move the context entries into the state?
                    is MenuState.None -> Unit
                }

                when (view) {
                    is View.Favorites -> LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
                            .pointerInput(Unit) {
                                while (true) {
                                    awaitPointerEventScope {
                                        Log.d("MainActivity", "pointerInput called")
                                        awaitPointerEvent(pass = PointerEventPass.Initial).changes.firstOrNull()
                                            ?.let {
                                                if (!letterBarBounds.contains(it.position) && it.positionChange().y > 50f) {
                                                    expandNotificationShade(context)
                                                    it.consume() // Block children from handling
                                                }
                                            }
                                    }
                                }
                            }) {
                        items(items = favorites2, key = { "fav-${it.label}" }) {
                            IconRow(it, ::rowAction)
                        }
                    }

                    is View.AllApps -> LazyColumn(
                        modifier = Modifier, state = listState, contentPadding = PaddingValues(
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
                                IconRow(row, ::rowAction)
                            }
                        }

//                        itemsIndexed(appListData.items) { _, item ->
//                            when (item) {
//                                is ListItem.Header -> {
//                                    Text(
//                                        text = item.letter.toString(),
//                                        fontSize = 24.sp,
//                                        color = primaryColor,
//                                        modifier = Modifier
//                                            .padding(16.dp)
//                                            .padding(start = 64.dp)
//                                    )
//                                }

//                                is ListItem.AppEntry -> {
//                                    val app = item.launchableInfo
//                                    if (app is Launchable.Shortcut) Log.d(
//                                        "Launcher_UI", "pwa: $app"
//                                    )
//                                    AppRow(
//                                        launchable = app,
//                                        launchApp = {
//                                            viewVM.setLeveTimeStamp(System.currentTimeMillis())
//                                            appsVM.launch(app)
//                                        },
//                                        onLongPress = {
//                                            showSheetForLaunchable = app
//                                            coroutineScope.launch { bottomSheetState.show() }
//                                        },
//                                        onLongSwipe = ::selectAppWithBounds,
//                                    )
//                                }
//                            }
//                        }
                    }
                }
//                var lastLetter by remember { mutableStateOf<Char?>(null) }
                LaunchedEffect(view) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                LetterBar(
//                    sortedLetters = appListData.letterToIndex.keys.toList(),
                    letters = allAppsBarIndex.keys.toList(),
                    view = view,
                    update = { index ->
                        // I get the index of the letter in the list of letters
                        // I now need to take the letter to lookup the index of the Char in the all apps map
                        // Can I not just skip the letter conversion and use the index to lookup the index in the map?
                        val selection = allAppsBarIndex.entries.elementAtOrNull(index)
                        if (selection == null) {
                            viewVM.setView(View.Favorites)
                            return@LetterBar
                        }
                        coroutineScope.launch { listState.scrollToItem(selection.value, 0) }
                        viewVM.setView(View.AllApps(selection.key))


//                        appListData.letterToIndex.entries.elementAtOrNull(index)
//                            ?.let { (letter, i) ->
//                                coroutineScope.launch {
//                                    listState.scrollToItem(
//                                        index = i, scrollOffset = 0
//                                    )
//                                }

//                                // This should be able to life in the Bar if you pass the haptic...
//                                if (letter != lastLetter) {
//                                    lastLetter = letter
//                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
//                                }
//                                viewVM.setView(View.AllApps(letter))
//                            } ?: viewVM.setView(View.Favorites)
                    },
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp) // Start 1/3 from the top
                        .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp) // End 1/8 from the bottom
                        .onGloballyPositioned { coordinates ->
                            // TODO: hard code this padding into the down swipe detection
                            // that would simplify this a lot
                            letterBarBounds = coordinates.boundsInWindow()
                        })
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (System.currentTimeMillis() - viewVM.leaveTimeStamp.value < 5000) viewVM.setLeveTimeStamp(
            0L
        ) else viewVM.setView(View.Favorites)
        Log.d("MainActivity", "new intent called")
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun LetterBar(
    modifier: Modifier = Modifier, color: Color, // TODO: can this handled via a theme?
    letters: List<Char>, view: View, update: (Int) -> Unit
) {
    var isScrollbarTouched by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxHeight()
            .width(if (isScrollbarTouched) 96.dp else 48.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isScrollbarTouched = true },
                    onDragEnd = { isScrollbarTouched = false },
                    onDrag = { change, _ -> update((change.position.y / size.height * letters.size).toInt()) })
            }) {
        if (view is View.AllApps) {
            letters.forEach { letter ->
                Text(
                    text = letter.toString(),
                    fontSize = if (letter == view.letter) 24.sp else 16.sp, // Make the selected letter bigger
                    color = if (letter == view.letter) Color.White else color,
                    modifier = Modifier
                )
            }
        }
    }
}

@Composable
fun IconRow(
    uiRow: UiRow,
    action: (UiRow, RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layoutCoordinates: LayoutCoordinates? = null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .padding(start = 48.dp)
            .onGloballyPositioned { coordinates -> layoutCoordinates = coordinates }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { action(uiRow, RowAction.Launch) },
                    onLongPress = { action(uiRow, RowAction.ShowSheet) })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, drag ->
                    if (drag > 50f) action(
                        uiRow, RowAction.ShowPopup(
                            layoutCoordinates?.boundsInWindow()?.bottom ?: error("no loc")
                        )
                    )
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

//@Composable
//fun AppRow(
//    launchable: Launchable,
//    modifier: Modifier = Modifier,
//    launchApp: () -> Unit,
//    onLongPress: () -> Unit,
//    onLongSwipe: (Launchable, Rect) -> Unit
//) {
//    var rowBounds by remember { mutableStateOf(Rect.Zero) }
//    Row(
//        verticalAlignment = Alignment.CenterVertically,
//        horizontalArrangement = Arrangement.spacedBy(24.dp),
//        modifier = modifier
//            .fillMaxWidth()
//            .padding(vertical = 8.dp)
//            .padding(start = 48.dp)
//            .onGloballyPositioned { coordinates ->
//                rowBounds = coordinates.boundsInWindow()
//            }
//            .pointerInput(Unit) {
//                detectTapGestures(onTap = { launchApp() }, onLongPress = { onLongPress() })
//            }
//            .pointerInput(Unit) {
//                detectHorizontalDragGestures { _, dragAmount ->
//                    Log.d("MainActivity", "dragAmount: $dragAmount")
//                    if (dragAmount > 50f) onLongSwipe(launchable, rowBounds)
//                }
//            }) {
//        if (launchable.icon != null) {
//            Image(
//                bitmap = launchable.icon!!,
//                contentDescription = launchable.name,
//                modifier = Modifier.size(42.dp)
//            )
//        } else Spacer(modifier = Modifier.width(42.dp))
//        Text(
//            text = launchable.name,
//            fontSize = 24.sp,
//            color = Color.White,
//            style = MaterialTheme.typography.labelMedium.copy(
//                shadow = Shadow(
//                    color = Color.Black, offset = Offset(0.01f, 0.01f), blurRadius = 5f
//                )
//            )
//        )
//    }
//}

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

// TODO: no shortcuts sometimes appears at top right of the screen instead of finger pos
// Next thing? Log the popup stuff
@Composable
fun ShortcutPopup(menu: MenuState.ListPopup, rowAction: (UiRow, RowAction) -> Unit) {
    val entries by menu.list.collectAsState()
    // TODO: Chrome shows no shortcuts | No app shows shortcuts :D
    Popup(properties = PopupProperties(focusable = true), onDismissRequest = menu.reset) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null, onClick = menu.reset)
        ) {
            var popupHeight by remember { mutableIntStateOf(0) }
            Column(
                modifier = Modifier
                    .onGloballyPositioned { popupHeight = it.size.height }
                    .offset(
                        x = 24.dp, y = with(LocalDensity.current) {
                            val y = menu.yPos - popupHeight
                            if (y > 0f) y.toDp() + 24.dp else 0.dp
                        })
//                    .width(with(density) { anchorBounds.width.toDp() }) // hard code this no?
                    // TODO: Does this work? main column has no width too
                    .background(Color(0xFF121212), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                if (entries.isNotEmpty()) {
                    entries.reversed().forEach { item -> IconRow(item, rowAction) }
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
fun ContextSheet(state: MenuState.ContextSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries by state.entries.collectAsState()

    ModalBottomSheet(
        onDismissRequest = state.reset,
        sheetState = sheetState,
        containerColor = Color(0xFF121212),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        windowInsets = WindowInsets(0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            entries.forEach { entry -> SheetEntry(entry.label, entry.onTap, state.reset) }
            Spacer(Modifier.height(42.dp))
        }
    }
}