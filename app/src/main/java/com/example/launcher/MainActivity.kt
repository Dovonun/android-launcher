package com.example.launcher

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch

data class App(
    val name: String, val packageName: String, val icon: ImageBitmap
)

fun App.launch(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    context.startActivity(intent)
}

fun getInstalledApps(context: Context): List<App> {
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = packageManager.queryIntentActivities(intent, 0)
    return resolveInfos.map { resolveInfo ->
        App(
            resolveInfo.loadLabel(packageManager).toString(),
            resolveInfo.activityInfo.packageName,
            resolveInfo.loadIcon(packageManager).toBitmap().asImageBitmap()
        )
    }
}

sealed class ListItem {
    data class Header(val letter: Char) : ListItem()
    data class AppEntry(val appInfo: App) : ListItem()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        )
        window.attributes.setWallpaperTouchEventsEnabled(false)

        enableEdgeToEdge()
        setContent {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
            val context = LocalContext.current

            val coroutineScope = rememberCoroutineScope()

            val wallpaperManager = WallpaperManager.getInstance(context)
            val wallpaperColors: WallpaperColors? =
                remember { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }

            val primaryColorInt: Int? = remember { wallpaperColors?.primaryColor?.toArgb() }
            val primaryColor = remember { primaryColorInt?.let { Color(it) } ?: Color.Black }
//            val primaryColorHsv = remember { FloatArray(3) }
//            val bright_primaryColor = remember { Color.hsv(primaryColor.hue, 1f, 1f) }
//            val statusBarManager =
//                context.getSystemService(Context.STATUS_BAR_SERVICE) as? StatusBarManager
            val listState = rememberLazyListState()

            var selectedLetter by remember { mutableStateOf<Char?>(null) }
            val installedApps by remember {
                mutableStateOf(getInstalledApps(context).sortedBy { it.name }
                    .groupBy { it.name[0].uppercaseChar() })
            }
            val (appList, letterIndices) = remember(installedApps) {
                val items = mutableListOf<ListItem>()
                val indices = mutableMapOf<Char, Int>()

                installedApps.entries.forEach { (letter, apps) ->
                    items.add(ListItem.Header(letter))
                    indices[letter] = items.lastIndex
                    apps.forEach { app ->
                        items.add(ListItem.AppEntry(app))
                    }
                }
                Pair(items, indices)
            }
//            val sectionIndices = remember(groupedApps) {
//                groupedApps.value.keys.map { it  -> to groupedApps.keys.takeWhile { k -> k != it }.size }.toMap()
//            }

            val sharedPreferences by remember {
                mutableStateOf(
                    context.getSharedPreferences(
                        "launcher_prefs", Context.MODE_PRIVATE
                    )
                )
            }
            var favorites by remember {
                mutableStateOf(
                    sharedPreferences.getStringSet("favorites", emptySet()) ?: emptySet()
                )
            }
            var selectedApp by remember { mutableStateOf<App?>(null) }
            if (selectedApp != null) {
                AlertDialog(onDismissRequest = { selectedApp = null },
                    title = { Text(text = selectedApp!!.name) },
                    text = {
                        Text(
                            if (favorites.contains(selectedApp!!.packageName)) {
                                "Remove ${selectedApp!!.name} from favorites?"
                            } else {
                                "Add ${selectedApp!!.name} to favorites?"
                            }
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val newFavorites = if (favorites.contains(selectedApp!!.packageName)) {
                                favorites.filter { it != selectedApp!!.packageName }.toSet()
                            } else {
                                favorites + selectedApp!!.packageName
                            }
                            sharedPreferences.edit().putStringSet("favorites", newFavorites).apply()
                            favorites = newFavorites
                            selectedApp = null
                        }) { Text("Yes") }
                    })
            }

//            val filteredApps = selectedLetter?.let { letter ->
//                installedApps.filter { it.name[0].uppercaseChar() == letter }
//            } ?: installedApps
//            Button(onClick = {
//                statusBarManager?.expandNotificationsPanel()
//            })
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.hsv(0f, 0.0f, 0f, 0.15f))
            ) {
                if (selectedLetter == null) {
                    LazyColumn(
                        reverseLayout = true,
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
                    ) {
                        items(installedApps.values.flatten()
                            .filter { app -> favorites.contains(app.packageName) },
                            key = { it.packageName }) { app ->
                            AppRow(app = app,
                                launchApp = { app.launch(context) },
                                toggleFavorites = { selectedApp = app })
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier, state = listState, contentPadding = PaddingValues(
                            top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp,
//                            top = (viewportHeight / 3).dp,
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
                                    AppRow(app = app,
                                        launchApp = { app.launch(context) },
                                        toggleFavorites = { selectedApp = app })
                                }
                            }
                        }
//                        groupedApps.value.forEach { (key, apps) ->
//                            // Section header
//                            item(key = "header_$key") {
//                                Text(
//                                    text = key.toString(),
//                                    fontSize = 24.sp,
//                                    color = primaryColor,
//                                    modifier = Modifier
//                                        .padding(16.dp)
//                                        .padding(start = 64.dp)
//                                )
//                            }
//                            // List of items under the section
//                            items(apps, key = { it.packageName }) { app ->
//                                AppRow(app = app,
//                                    launchApp = { app.launch(context) },
//                                    toggleFavorites = { selectedApp = app })
//                            }
//                        }
                    }
//                    LazyColumn(
//                        modifier = Modifier.padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp)
//                    ) {
//                        item(key = "header") {
//                            Text(
//                                text = selectedLetter.toString(),
//                                fontSize = 24.sp,
//                                color = primaryColor,
//                                modifier = Modifier
//                                    .padding(16.dp)
//                                    .padding(start = 64.dp)
//                            )
//                        }
//                        items(filteredApps, key = { it.packageName }) { app ->
//                            AppRow(app = app,
//                                launchApp = { app.launch(context) },
//                                toggleFavorites = { selectedApp = app })
//                        }
//                    }
                }
                LetterBar(
                    sortedLetters = letterIndices.keys.toList(),
                    setSelectedLetter = { newLetter ->
                        selectedLetter = newLetter
                        coroutineScope.launch {
                            val targetIndex = letterIndices[newLetter] ?: return@launch
                            listState.scrollToItem(
                                index = targetIndex,
                                scrollOffset = 0
                            )
                        }
                    },
                    color = primaryColor,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp) // Start 1/3 from the top
                        .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp) // End 1/8 from the bottom
                )
            }
        }
    }
}

@Composable
fun LetterBar(
    modifier: Modifier = Modifier,
    color: Color,
    sortedLetters: List<Char>,
    setSelectedLetter: (Char?) -> Unit
) {
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    Row(modifier = modifier) {
        var isScrollbarTouched by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxHeight()
                .width(if (isScrollbarTouched) 96.dp else 48.dp)
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { isScrollbarTouched = true },
                        onDragEnd = { isScrollbarTouched = false },
                        onDrag = { change, _ ->
                            val letterIndex =
                                (change.position.y / size.height * sortedLetters.size).toInt()
                            if (letterIndex in sortedLetters.indices) {
                                selectedLetter = sortedLetters[letterIndex]
                                setSelectedLetter(selectedLetter)
                            } else {
                                selectedLetter = null
                                setSelectedLetter(null)
                            }
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
    app: App, modifier: Modifier = Modifier, launchApp: () -> Unit, toggleFavorites: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 48.dp)
            .padding(vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { launchApp() }, onLongPress = { toggleFavorites() })
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
//            modifier = Modifier.shadow(1.dp, shape = RectangleShape)
//        )
    }
}