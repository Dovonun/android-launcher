package com.example.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                LazyColumn(state = lazyListState) {
                    items(filteredApps) { app ->
                        AppRow(app = app) {
                            app.launch(context)
                        }
                    }
                }


                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                ) {
                    // Selected letter
                    if (isScrollbarTouched && selectedLetter != null) {
                        Text(
                            text = selectedLetter.toString(),
                            fontSize = 24.sp, // Make the letter bigger
                            color = Color.Red,
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(end = 8.dp) // Add some spacing
                        )
                    }

                    // bar
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(1f - (1f / 3f + 1f / 8f))
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
                    ) {
                        if (isScrollbarTouched) {
                            // Display the alphabet vertically
                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(8.dp)
                            ) {
                                sortedLetters.forEach { letter ->
                                    Text(
                                        text = letter.toString(),
                                        fontSize = 32.sp, // Adjust the size as needed
                                        color = if (letter == selectedLetter) Color.Red else Color.Gray,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }
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