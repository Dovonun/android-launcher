package com.example.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
//import androidx.compose.foundation.layout.FlowRowScopeInstance.weight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?
)

fun AppInfo.launch(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    context.startActivity(intent)
}

fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) {
        context.startActivity(intent)
    }
}

fun getInstalledApps(context: Context): List<AppInfo> {
    val packageManager = context.packageManager

    // Query all apps with a launcher intent (apps that can be launched)
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfos = packageManager.queryIntentActivities(intent, 0)
    return resolveInfos.map { resolveInfo ->
        AppInfo(
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
//            val iconBitmap: ImageBitmap? = remember {
//                val drawable =
//                    ContextCompat.getDrawable(context, R.drawable.ic_android_black_24dp)
//                drawable?.let {
//                    val bitmap = Bitmap.createBitmap(
//                        drawable.intrinsicWidth,
//                        drawable.intrinsicHeight,
//                        android.graphics.Bitmap.Config.ARGB_8888
//                    )
//                    val canvas = Canvas(bitmap)
//                    drawable.setBounds(0, 0, canvas.width, canvas.height)
//                    drawable.draw(canvas)
//                    bitmap.asImageBitmap()
//                }
//            }
//            Icon(bitmap = iconBitmap!!, contentDescription = "Android Icon")
//            AppRow(app = AppInfo("foo", "bar", null))


            val installedApps =
                remember { mutableStateOf(getInstalledApps(context).sortedBy { it.name }) }

            // Display the list of apps
            LazyColumn {
                items(installedApps.value) { app ->
                    AppRow(app = app) {
                        app.launch(context)
                    }
                }
            }
        }
    }
}

@Composable
fun AppRow(app: AppInfo, modifier: Modifier = Modifier, onClick: () -> Unit) {


//    val appIcon: ImageBitmap? = remember(app.icon) {
//        val bitmap = Bitmap.createBitmap(
//            app.icon.intrinsicWidth,
//            app.icon.intrinsicHeight,
//            Bitmap.Config.ARGB_8888
//        )
//        val canvas = Canvas(bitmap)
//        app.icon?.setBounds(0, 0, canvas.width, canvas.height)
//        app.icon?.draw(canvas)
//        bitmap.asImageBitmap()
//    }
    Row(
        verticalAlignment = Alignment.CenterVertically, modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(onClick = onClick)
    ) {

        Spacer(modifier = Modifier.width(64.dp))
        app.icon?.let { icon ->
            Image(
                bitmap = icon,
                contentDescription = app.name,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.width(32.dp))
        Text(
            text = app.name, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
        )
    }
}

//@Composable
//fun AppListScreen(modifier: Modifier = Modifier) {
//    val context = LocalContext.current
//    val apps: List<App> = remember {
//        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
//
//        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
//            .map { resolveInfo: ResolveInfo ->
//                App(
//                    name = resolveInfo.loadLabel(context.packageManager).toString(),
//                    packageName = resolveInfo.activityInfo.packageName,
//                    icon = resolveInfo.loadIcon(context.packageManager)
//                )
//            }
//    }
//    LazyColumn(modifier = modifier) {
//        items(apps) { app ->
//            AppRow(app = app)
//        }
//    }
//}