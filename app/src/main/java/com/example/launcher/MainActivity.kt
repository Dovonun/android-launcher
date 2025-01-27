package com.example.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.core.graphics.drawable.toBitmap

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

            val installedApps by remember { mutableStateOf(getInstalledApps(context).sortedBy { it.name }) }
            val groupedApps =
                remember { mutableStateOf(installedApps.groupBy { it.name[0].uppercaseChar() }) }
            val sortedLetters = remember { groupedApps.value.keys.sorted() }
            var selectedLetter by remember { mutableStateOf<Char?>(null) }

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

            val filteredApps = selectedLetter?.let { letter ->
                installedApps.filter { it.name[0].uppercaseChar() == letter }
            } ?: installedApps

            //debug texts
            Column {
                Text(text = selectedLetter?.toString() ?: "Foo")
                Text(text = selectedApp?.toString() ?: "Selected App")
                Text(text = favorites.toString() ?: "Selected App")
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedLetter == null) {
                    LazyColumn(
                        modifier = Modifier.padding(top = 1f / 4f * LocalConfiguration.current.screenHeightDp.dp)
                    ) {
                        items(installedApps.filter { app -> favorites.contains(app.packageName) },
                            key = { it.packageName }) { app ->
                            AppRow(app = app,
                                launchApp = { app.launch(context) },
                                toggleFavorites = { selectedApp = app })
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(top = 1f / 4f * LocalConfiguration.current.screenHeightDp.dp)
                    ) {
                        item(key = "header") {
                            Text(
                                text = selectedLetter.toString(),
                                fontSize = 24.sp,
                                color = Color.Black,
                                modifier = Modifier
                                    .padding(16.dp)
                                    .padding(start = 64.dp)
                            )
                        }
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppRow(app = app,
                                launchApp = { app.launch(context) },
                                toggleFavorites = { selectedApp = app })
                        }
                    }
                }
                LetterBar(
                    sortedLetters = sortedLetters,
                    setSelectedLetter = { selectedLetter = it },
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
    modifier: Modifier = Modifier, sortedLetters: List<Char>, setSelectedLetter: (Char?) -> Unit
) {
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    Row(modifier = modifier) {
        var isScrollbarTouched by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxHeight()
                .width(if (isScrollbarTouched) 96.dp else 48.dp)
                .border(1.dp, Color.Gray)
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { isScrollbarTouched = true },
                        onDragEnd = { isScrollbarTouched = false },
                        onDrag = { change, _ ->
                            val letterIndex =
                                (change.position.y / size.height * sortedLetters.size).toInt()
                            selectedLetter = sortedLetters[letterIndex]
                            setSelectedLetter(selectedLetter)
                        })
                }) {
            sortedLetters.forEach { letter ->
                if (selectedLetter != null) {
                    Text(
                        text = letter.toString(),
                        fontSize = if (letter == selectedLetter) 24.sp else 16.sp, // Make the selected letter bigger
                        color = if (letter == selectedLetter) Color.Red else Color.Gray,
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
            .padding(start = 64.dp)
            .padding(8.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { launchApp() }, onLongPress = { toggleFavorites() })
            }) {
        Image(bitmap = app.icon, contentDescription = app.name, modifier = modifier.size(42.dp))
        Spacer(modifier = modifier.width(32.dp))
        Text(text = app.packageName, style = MaterialTheme.typography.bodyLarge)
    }
}