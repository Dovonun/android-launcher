package com.example.launcher

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.window.Popup
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import android.view.WindowInsets as ViewWindowInsets

sealed interface View {
    data object Favorites : View
    data class AllApps(val letter: Char) : View
}

data class UiShortcut(
    val label: String,
    val icon: ImageBitmap,
)

data class App(
    val name: String, val packageName: String, val icon: ImageBitmap
)

fun App.launch(context: Context) {
    context.startActivity(context.packageManager.getLaunchIntentForPackage(packageName) ?: return)
}

sealed class ListItem {
    data class Header(val letter: Char) : ListItem()
    data class AppEntry(val appInfo: App) : ListItem()
}

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
        window.setBackgroundDrawableResource(android.R.color.transparent)

        setContent {
            window.insetsController?.hide(ViewWindowInsets.Type.statusBars())
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()

            val appsVM: AppsVM = viewModel()
            val appListData by appsVM.appListData.collectAsState()
            val favorites by appsVM.favoriteApps.collectAsState()
            var screenState: View by remember { mutableStateOf(View.Favorites) }

            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperColors: WallpaperColors? = remember { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }


            val primaryColorInt: Int? = remember { wallpaperColors?.primaryColor?.toArgb() }
            val primaryColor = remember { primaryColorInt?.let { Color(it) } ?: Color.Black }
//            val primaryColorHsv = remember { FloatArray(3) }
//            val bright_primaryColor = remember { Color.hsv(primaryColor.hue, 1f, 1f) }
            val listState = rememberLazyListState()

            val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var showSheetForApp by remember { mutableStateOf<App?>(null) }
            var letterBarBounds by remember { mutableStateOf(Rect.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
//                    .border(1.dp, Color.White)
                    .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
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

                if (appsVM.selectedApp.collectAsState().value != null) {
                    ShortcutPopup(
                        shortcuts = appsVM.shortcutUiItems.collectAsState().value,
                        launch = { index -> appsVM.launchShortcut(index) },
                        reset = { appsVM.selectApp(null) }
                    )
                }

                LaunchedEffect(showSheetForApp) { if (showSheetForApp == null && bottomSheetState.isVisible) bottomSheetState.hide() }
                if (showSheetForApp != null) {
                    ModalBottomSheet(
                        onDismissRequest = { showSheetForApp = null },
                        sheetState = bottomSheetState,
                        containerColor = Color(0xFF121212),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        windowInsets = WindowInsets(0.dp)
                    ) {
                        val app = showSheetForApp!!
                        Column(Modifier.fillMaxWidth()) {
                            // TOOD: use material icons
                            SheetEntry(
                                if (appsVM.isFavorite(app.packageName)) "Remove from favorites" else "Add to favorites"
                            ) {
                                appsVM.toggleFavorite(app.packageName)
                                showSheetForApp = null
                            }
                            SheetEntry("Uninstall") {
                                val intent = Intent(Intent.ACTION_DELETE)
                                intent.data = "package:${app.packageName}".toUri()
                                context.startActivity(intent)
                                showSheetForApp = null
                            }
                            SheetEntry("App settings") {
                                val intent =
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                intent.data = "package:${app.packageName}".toUri()
                                context.startActivity(intent)
                                showSheetForApp = null
                            }
                            Spacer(Modifier.height(42.dp))
                        }
                    }
                }

                when (screenState) {
                    is View.Favorites -> LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
                            .clickable { /* No-op, just to claim touch priority */ } // TODO: Is this the flicker issue?
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
                        items(favorites, key = { "fav-${it.packageName}" }) { app ->
                            AppRow(app = app, launchApp = { app.launch(context) }, onLongPress = {
                                showSheetForApp = app
                                coroutineScope.launch { bottomSheetState.show() }
                            }, onLongSwipe = { target, bounds ->
                                Log.d("MainActivity", "long swipe")
                                appsVM.selectApp(target)
                            })
                        }
                    }

                    is View.AllApps -> LazyColumn(
                        modifier = Modifier, state = listState, contentPadding = PaddingValues(
                            top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp,
                            bottom = 2f / 3f * LocalConfiguration.current.screenHeightDp.dp
                        )
                    ) {
                        itemsIndexed(appListData.items) { _, item ->
                            when (item) {
                                is ListItem.Header -> {
                                    Text(
                                        text = item.letter.toString(),
                                        fontSize = 24.sp,
                                        color = primaryColor,
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .padding(start = 64.dp)
                                    )
                                }

                                is ListItem.AppEntry -> {
                                    val app = item.appInfo
                                    AppRow(
                                        app = app,
                                        launchApp = { app.launch(context) },
                                        onLongPress = {
                                            showSheetForApp = app
                                            coroutineScope.launch { bottomSheetState.show() }
                                        },
                                        onLongSwipe = { tappedApp, bounds ->
                                            appsVM.selectApp(tappedApp)
                                        })
                                }
                            }
                        }
                    }
                }
//                            update(
//                                currentSortedLetters.getOrNull((change.position.y / size.height * currentSortedLetters.size).toInt())
//                                    ?.let { View.AllApps(it) } ?: View.Favorites)
                LetterBar(
                    sortedLetters = appListData.letterToIndex.keys.toList(),
                    view = viewVM.view.collectAsState().value,
                    update = { index ->
                        appListData.letterToIndex.entries.elementAtOrNull(index)?.let { (letter, i) ->
                            coroutineScope.launch { listState.scrollToItem(index = i, scrollOffset = 0) }
                            viewVM.setView(View.AllApps(letter))
                        } ?: viewVM.setView(View.Favorites)
                    },
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp) // Start 1/3 from the top
                        .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp) // End 1/8 from the bottom
                        .onGloballyPositioned { coordinates ->
                            letterBarBounds = coordinates.boundsInWindow()
                        })
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        viewVM.setView(View.Favorites)
        Log.d("MainActivity", "new intent called")
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

@Composable
fun LetterBar(
    modifier: Modifier = Modifier,
    color: Color,
    sortedLetters: List<Char>,
    view: View,
    update: (Int) -> Unit
) {
    val currentSortedLetters by rememberUpdatedState(sortedLetters)
    Row(modifier = modifier) {
        var isScrollbarTouched by remember { mutableStateOf(false) }
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxHeight()
                .width(if (isScrollbarTouched) 96.dp else 48.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isScrollbarTouched = true },
                        onDragEnd = { isScrollbarTouched = false },
                        onDrag = { change, _ -> update((change.position.y / size.height * currentSortedLetters.size).toInt()) })
                }) {
            if (view is View.AllApps) {
                sortedLetters.forEach { letter ->
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
}

@Composable
fun MenuRow(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageBitmap? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp) // Adjusted vertical padding
    ) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = text, modifier = Modifier.size(42.dp))
            Spacer(modifier = Modifier.width(32.dp))
        } else {
            // Spacer to align with AppRow's text when there's no icon
            Spacer(modifier = Modifier.width(42.dp + 32.dp))
        }
        Text(
            text = text,
            fontSize = 24.sp,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = Shadow(
                    color = Color.Black, offset = Offset(0.01f, 0.01f), blurRadius = 5f
                )
            )
        )
    }
}

@Composable
fun AppRow(
    app: App,
    modifier: Modifier = Modifier,
    launchApp: () -> Unit,
    onLongPress: () -> Unit,
    onLongSwipe: (App, Rect) -> Unit
) {
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .onGloballyPositioned { coordinates ->
                rowBounds = coordinates.boundsInWindow()
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { launchApp() }, onLongPress = { onLongPress() })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    Log.d("MainActivity", "dragAmount: $dragAmount")
                    if (dragAmount > 50f) onLongSwipe(app, rowBounds)
                }
            }) {
        Row(modifier = Modifier.padding(start = 48.dp)) {
            Image(bitmap = app.icon, contentDescription = app.name, modifier = Modifier.size(42.dp))
            Spacer(modifier = Modifier.width(32.dp))
            Text(
                text = app.name,
                fontSize = 24.sp,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    shadow = Shadow(
                        color = Color.Black, offset = Offset(0.01f, 0.01f), blurRadius = 5f
                    )
                )
            )
        }
    }
}

@Composable
fun SheetEntry(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(42.dp) // same as AppRow height
    ) {
        Spacer(modifier = Modifier.width(74.dp)) // for icon space (42 + 32 spacing)
        Text(text = text, fontSize = 24.sp, color = Color.White)
    }
}

@Composable
fun ShortcutPopup(shortcuts: List<UiShortcut>, launch: (Int) -> Unit, reset: () -> Unit) {
    val anchorBounds = Rect.Zero
    Popup(
        offset = IntOffset(anchorBounds.left.toInt(), anchorBounds.top.toInt()),
        onDismissRequest = reset,
    ) {
        Box(
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorBounds.width.toDp() })
                .padding(horizontal = 48.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121212), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    shortcuts.forEachIndexed { index, s ->
                        MenuRow(
                            text = s.label,
                            icon = s.icon,
                            onClick = { launch(index) }
                        )
                    }
                }
            }
        }
    }
}