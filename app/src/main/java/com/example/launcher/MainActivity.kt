package com.example.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?
)

fun App.launch(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    context.startActivity(intent)
}

fun getInstalledApps(context: Context): List<App> {
    val packageManager = context.packageManager

    // Query all apps with a launcher intent (apps that can be launched)
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
        enableEdgeToEdge()
        setContent {
            window.insetsController?.hide(WindowInsets.Type.statusBars())

            val context = LocalContext.current
            val installedApps = getInstalledApps(context).sortedBy { it.name }
            val groupedApps =
                remember { mutableStateOf(installedApps.groupBy { it.name[0].uppercaseChar() }) }
            val sortedLetters = remember { groupedApps.value.keys.sorted() }

            var selectedLetter by remember { mutableStateOf<Char?>(null) }
            var isScrollbarTouched by remember { mutableStateOf(false) }
            val lazyListState = rememberLazyListState()

            LaunchedEffect(selectedLetter) {
                selectedLetter?.let { letter ->
                    val index = installedApps.indexOfFirst { it.name[0].uppercaseChar() == letter }
                    if (index != -1) {
                        lazyListState.scrollToItem(index)
//                        vibrate()
                    }
                }
            }
            val filteredApps = selectedLetter?.let { letter ->
                installedApps.filter { it.name[0].uppercaseChar() == letter }
            } ?: installedApps


            Box(modifier = Modifier.fillMaxSize()) {
                // Display the list of apps
                LazyColumn(
                    state = lazyListState, modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 1f / 4f * LocalConfiguration.current.screenHeightDp.dp)
                ) {
                    // Add the selected letter as a header
                    selectedLetter?.let { letter ->
                        item {
                            Text(
                                text = letter.toString(),
                                fontSize = 24.sp,
                                color = Color.Black,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .padding(start = 64.dp)
                            )
                        }
                    }
                    items(filteredApps) { app ->
                        AppRow(app = app) {
                            app.launch(context)
                        }
                    }
                }


                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp) // Start 1/3 from the top
                        .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp) // End 1/8 from the bottom
                        .border(1.dp, Color.Green)
                ) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .border(1.dp, Color.Blue)
                    ) {
                        // Selected letter
                        if (isScrollbarTouched) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(48.dp)
                                    .background(Color.Transparent)
                            ) {
                                // Display the alphabet vertically
                                Column(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.SpaceBetween // Evenly distribute letters
                                ) {
                                    sortedLetters.forEach { letter ->
                                        Text(
                                            text = letter.toString(),
                                            fontSize = if (letter == selectedLetter) 24.sp else 16.sp, // Make the selected letter bigger
                                            color = if (letter == selectedLetter) Color.Red else Color.Gray,
                                            modifier = Modifier.padding(2.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(48.dp)
                                .background(Color.Transparent)
                                .border(1.dp, Color.Gray)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            isScrollbarTouched = true
                                        },
                                        onDragEnd = {
                                            isScrollbarTouched = false
                                        },
                                        onDrag = { change, _ ->
                                            val letterIndex =
                                                (change.position.y / size.height * sortedLetters.size).toInt()
                                            selectedLetter = sortedLetters.getOrNull(letterIndex)
                                        }
                                    )
                                }
                        )
                    }
                }
//                AlphabetScrollbar(
//                    letters = sortedLetters,
//                    onLetterSelected = { letter ->
//                        selectedLetter = letter
//                    }
//                )
            }
        }
    }
}

@Composable
fun AppRow(app: App, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically, modifier = modifier
            .fillMaxWidth()
            .padding(start = 64.dp)
            .padding(8.dp)
            .clickable(onClick = onClick)
    ) {
        app.icon?.let { icon ->
            Image(
                bitmap = icon,
                contentDescription = app.name,
                modifier = modifier.size(42.dp)
            )
        }
        Spacer(modifier = modifier.width(32.dp))
        Text(
            text = app.name, style = MaterialTheme.typography.bodyLarge,
            modifier = modifier
        )
    }
}
//
//@Composable
//fun AlphabetScrollbar(
//    letters: List<Char>,
//    onLetterSelected: (Char) -> Unit
//) {
//    Box(
//        modifier = Modifier
//            .align(Alignment.CenterEnd) // Align to the right
//            .fillMaxHeight()
//            .width(48.dp)
//            .background(Color.Transparent)
//            .pointerInput(Unit) {
//                var onDragStart = {
//                    isScrollbarTouched = true
//                },
//                var onDragEnd = {
//                    isScrollbarTouched = false
//                },
//                var onDrag = { change, _ ->
//                    val letterIndex =
//                        (change.position.y / size.height * letters.size).toInt()
//                    val selectedLetter = letters.getOrNull(letterIndex)
//                    selectedLetter?.let { onLetterSelected(it) }
//                }
//            }
//    ) {
//        if (isScrollbarTouched) {
//            // Display the alphabet vertically
//            Column(
//                modifier = Modifier
//                    .align(Alignment.CenterEnd)
//                    .padding(8.dp)
//            ) {
//                letters.forEach { letter ->
//                    Text(
//                        text = letter.toString(),
//                        color = if (letter == selectedLetter) Color.White else Color.Gray,
//                        modifier = Modifier.padding(2.dp)
//                    )
//                }
//            }
//        }
//    }
//}