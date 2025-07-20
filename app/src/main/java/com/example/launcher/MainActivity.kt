package com.example.launcher

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import android.view.WindowInsets as ViewWindowInsets

data class App(
    val name: String, val packageName: String, val icon: ImageBitmap
)

fun App.launch(context: Context) {
    context.startActivity(context.packageManager.getLaunchIntentForPackage(packageName) ?: return)
}

class AppChangeCallback(private val onChanged: () -> Unit) : LauncherApps.Callback() {
    override fun onPackageAdded(packageName: String, user: UserHandle) = onChanged()
    override fun onPackageRemoved(packageName: String, user: UserHandle) = onChanged()
    override fun onPackageChanged(packageName: String, user: UserHandle) = onChanged()
    override fun onPackagesAvailable( packageNames: Array<out String?>?, user: UserHandle, replacing: Boolean ) { onChanged() }
    override fun onPackagesUnavailable( packageNames: Array<out String?>?, user: UserHandle?, replacing: Boolean ) { onChanged() }
}

fun getInstalledApps(user: UserHandle, service: LauncherApps): Map<Char, List<App>> {
    return service.getActivityList(null, user).map { app ->
        App( app.label.toString(), app.componentName.packageName, app.getIcon(0).toBitmap().asImageBitmap() )
    }.sortedBy { it.name.lowercase() }.groupBy { it.name[0].uppercaseChar() }
}

fun getShortcutsForApp( user: UserHandle, service: LauncherApps, packageName: String ): List<ShortcutInfo> {
    Log.d("MainActivity", "getShortcutsForApp called with $packageName")
    val query = ShortcutQuery().apply {
        // TODO: remove hidden/disabled shortcuts | check if that is even needed
        setPackage(packageName)
        setQueryFlags( ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_PINNED or ShortcutQuery.FLAG_MATCH_MANIFEST)
    }
    val shortcuts = service.getShortcuts(query, user)
    Log.d("MainActivity", "getShortcutsForApp returned $shortcuts")
    return shortcuts ?: emptyList()
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
    private var selectedLetter: Char? by mutableStateOf(null)

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("ReturnFromAwaitPointerEventScope")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        val userHandle = android.os.Process.myUserHandle()

        window.attributes.setWallpaperTouchEventsEnabled(false)

        window.setBackgroundDrawableResource(android.R.color.transparent)

        setContent {
            window.insetsController?.hide(ViewWindowInsets.Type.statusBars())
            val context = LocalContext.current
            val coroutineScope = rememberCoroutineScope()
            var installedAppsState by remember { mutableStateOf<Map<Char, List<App>>>( getInstalledApps(userHandle, launcherApps) ) }
            DisposableEffect(Unit) {
                val callback = AppChangeCallback {installedAppsState = getInstalledApps(userHandle, launcherApps)}
                launcherApps.registerCallback(callback)
                onDispose { launcherApps.unregisterCallback(callback) }
            }
            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperColors: WallpaperColors? =
                remember { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }

            val primaryColorInt: Int? = remember { wallpaperColors?.primaryColor?.toArgb() }
            val primaryColor = remember { primaryColorInt?.let { Color(it) } ?: Color.Black }
//            val primaryColorHsv = remember { FloatArray(3) }
//            val bright_primaryColor = remember { Color.hsv(primaryColor.hue, 1f, 1f) }
            val listState = rememberLazyListState()

            val (appList, letterIndices) = remember(installedAppsState) {
                Log.d("AppListDebug", "Recomputing appList and letterIndices")
                val items = mutableListOf<ListItem>()
                val indices = mutableMapOf<Char, Int>()

                installedAppsState.entries.forEach { (letter, apps) ->
                    Log.d("AppListDebug", "Adding section $letter with ${apps.size} apps")
                    indices[letter] = items.size
                    items.add(ListItem.Header(letter))
                    apps.forEach { app ->
                        items.add(ListItem.AppEntry(app))
                    }
                }
                Pair(items, indices)
            }
            val currentLetterIndices by rememberUpdatedState(letterIndices)

            val sharedPreferences by remember {
                mutableStateOf(
                    context.getSharedPreferences(
                        "launcher_prefs", MODE_PRIVATE
                    )
                )
            }
            var favorites by remember {
                mutableStateOf(
                    sharedPreferences.getStringSet(
                        "favorites", emptySet()
                    )?.toSet() ?: emptySet()
                )
            }

            val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var showSheetForApp by remember { mutableStateOf<App?>(null) }
            var letterBarBounds by remember { mutableStateOf(Rect.Zero) }

            var selectedApp by remember { mutableStateOf<App?>(null) }
            var anchorBounds by remember { mutableStateOf(Rect.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
//                    .border(1.dp, Color.White)
                    .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                if (dragAmount.y < 0) {
                                    selectedLetter = null
                                    change.consume()
                                }
                            },
                        )
                    }) {
                LaunchedEffect(showSheetForApp) { if (showSheetForApp == null && bottomSheetState.isVisible) bottomSheetState.hide() }

                DropdownMenu(
                    expanded = selectedApp != null,
                    onDismissRequest = { selectedApp = null },
                    offset = DpOffset(
                        x = with(LocalDensity.current) { anchorBounds.left.toDp() },
                        y = with(LocalDensity.current) {
                            val showAbove =
                                anchorBounds.top > LocalConfiguration.current.screenHeightDp.dp.toPx() / 2
                            if (showAbove) (anchorBounds.top - 100).toDp() else anchorBounds.bottom.toDp()
                        })
                ) {
                    var shortcuts =
                        remember(selectedApp) { mutableStateOf<List<ShortcutInfo>>(emptyList()) }

                    LaunchedEffect(selectedApp) {
                        selectedApp?.let { app ->
                            shortcuts.value =
                                getShortcutsForApp(userHandle, launcherApps, app.packageName)
                        }
                    }
                    shortcuts.value.forEach { s ->
                        DropdownMenuItem(text = {
                            Text(
                                s.shortLabel?.toString() ?: s.longLabel.toString()
                            )
                        }, onClick = {
                            launcherApps.startShortcut( s.`package`, s.id, null, null, userHandle )
                            selectedApp = null // dismiss here
                        })
                    }
                }

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
                            SheetEntry(
                                if (favorites.contains(app.packageName)) "Remove from favorites" else "Add to favorites"
                            ) {
                                val newFavorites =
                                    if (favorites.contains(app.packageName)) favorites - app.packageName else favorites + app.packageName
                                sharedPreferences.edit { putStringSet("favorites", newFavorites) }
                                favorites = newFavorites
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

                if (selectedLetter == null) { // show favorites
                    LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
                            .clickable { /* No-op, just to claim touch priority */ }
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
                        val appMap =
                            installedAppsState.values.flatten().associateBy { it.packageName }
                        val favoriteAppList = favorites.mapNotNull { appMap[it] }
                        items(
                            favoriteAppList, key = { "fav-${it.packageName}" }) { app ->
                            AppRow(app = app, launchApp = { app.launch(context) }, onLongPress = {
                                showSheetForApp = app
                                coroutineScope.launch { bottomSheetState.show() }
                            }, onLongSwipe = { tappedApp, bounds ->
                                Log.d("MainActivity", "long swipe")
                                selectedApp = tappedApp
                                anchorBounds = bounds
                            })
                        }
                    }
                } else { // show all apps
                    LazyColumn(
                        modifier = Modifier, state = listState, contentPadding = PaddingValues(
                            top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp,
                            bottom = 2f / 3f * LocalConfiguration.current.screenHeightDp.dp
                        )
                    ) {
                        itemsIndexed(appList) { _, item ->
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
                                            selectedApp = tappedApp
                                            anchorBounds = bounds
                                        })
                                }
                            }
                        }
                    }
                }
                LetterBar(
                    sortedLetters = letterIndices.keys.toList(),
                    selectedLetter = selectedLetter,
                    setSelectedLetter = { newLetter ->
                        selectedLetter = newLetter
                        coroutineScope.launch {
                            val targetIndex = currentLetterIndices[newLetter] ?: return@launch
                            listState.scrollToItem(index = targetIndex, scrollOffset = 0)
                        }
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
        selectedLetter = null
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
    selectedLetter: Char?,
    setSelectedLetter: (Char?) -> Unit
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
                        onDrag = { change, _ ->
                            val letterIndex = (change.position.y / size.height * currentSortedLetters.size).toInt()
                            setSelectedLetter(if (letterIndex in currentSortedLetters.indices) currentSortedLetters[letterIndex] else null)
                        })
                }) {
            sortedLetters.forEach { letter ->
                if (selectedLetter != null) {
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
            .padding(start = 48.dp)
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
        Text( text = text, fontSize = 24.sp, color = Color.White )
    }
}
