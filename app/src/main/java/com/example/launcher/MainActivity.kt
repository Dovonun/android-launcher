package com.example.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

const val H_PAD = 24
const val H_PAD2 = 2 * H_PAD

private fun buildBarIndex(apps: Map<Char, List<UiRow>>): List<Int> = buildList {
    apps.values.fold(0) { acc, list ->
        add(acc)
        acc + list.size + 1 // +1 for the header
    }
}

// TODO: Next AI chat about themes | Material3 in compose
// TODO: Next Make it look nice. Can you do the outline thing on text?
// Current version is more bold. Seems better
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
                val density = LocalDensity.current
                val style = TextStyle(
                    fontSize = 5.8213773.sp,
                    lineHeight = 1.sp,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Top,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                // TODO: Next how big is 5.sp in px on 2x scale?
                Box(modifier = Modifier.padding(50.dp)){
                    Text("Launcher", style = style )

                }
                val appsVM: AppsVM = viewModel()
                val systemVM: SystemVM = viewModel()
                val listState = rememberLazyListState()

                val menu by viewVM.menu.collectAsState()
                when (val state = menu) {
                    is MenuState.Popup -> ShortcutPopup(state, appsVM, viewVM)
                    is MenuState.Sheet -> ContextSheet(
                        state, appsVM
                    ) { viewVM.setMenu(MenuState.None) }

                    is MenuState.None -> Unit
                }
                val allApps by appsVM.uiAllGrouped.collectAsState()
                val scrollIndexes by remember(allApps) { derivedStateOf { buildBarIndex(allApps) } }
                val favorites by appsVM.favorites.collectAsState()
                val view by viewVM.view.collectAsState()
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
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            shadow = Shadow(
                                                color = MaterialTheme.colorScheme.primary,
                                                offset = Offset(0.01f, 0.01f),
                                                blurRadius = 5f
                                            )
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .padding(start = 16.dp)
                                    )
                                }
                                // TODO: What happens when 2 apps have the same name?
                                items(items = list, key = { "all-${it.label}" }) { row ->
                                    IconRow(row, appsVM, viewVM)
                                }
                            }
                        }
                    }
                    val coroutineScope = rememberCoroutineScope()
                    LetterBar(
                        view = view, letters = allApps.keys.toList(), update = { index ->
                            val scrollI = scrollIndexes.elementAtOrNull(index)
                            if (scrollI == null) viewVM.setView(View.Favorites) else {
                                viewVM.setView(View.AllApps(scrollI))
                                coroutineScope.launch {
                                    listState.scrollToItem(scrollI)
                                }
                            }
                        }, modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        viewVM.softReset()
    }
}

//@Composable
//fun LetterBar(
//    letters: List<Char>, view: View, update: (Int) -> Unit, modifier: Modifier = Modifier
//) {
//    val context = LocalContext.current
//    val density = LocalDensity.current
//    val haptic = LocalHapticFeedback.current
//    var isScrollbarTouched by remember { mutableStateOf(false) }
//    var selectedLetter by remember { mutableStateOf<Char?>(null) }
//
//    val textMeasurer = rememberTextMeasurer()
//    val textStyle = MaterialTheme.typography.headlineSmall
//    val textLayout = textMeasurer.measure(
//        text = AnnotatedString("A"),
//        style = textStyle
//    )
//    val letterHeightPx = textLayout.size.height.toFloat()
//    val totalHeightPx = letterHeightPx * letters.size
//    val totalHeightDp = with(density) { totalHeightPx.toDp() }
//
//    var layoutHeightPx by remember { mutableFloatStateOf(0f) }
//    Column(
//        verticalArrangement = Arrangement.SpaceBetween,
//        modifier = modifier
//            .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp) // Start 1/3 from the top
//            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp) // End 1/8 from the bottom
//            .fillMaxHeight()
//            .width(if (isScrollbarTouched) (2 * H_PAD2).dp else H_PAD2.dp)
//            .pointerInput(Unit) {
//                detectVerticalDragGestures(
//                    onDragStart = { isScrollbarTouched = true },
//                    onDragEnd = { isScrollbarTouched = false },
//                    onVerticalDrag = { change, _ ->
//                        change.consume()
//                        val index = (change.position.y / size.height * letters.size).toInt()
//                        val letter = letters.getOrNull(index)
//                        if (letter != selectedLetter) {
//                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
//                            selectedLetter = letter
//                            update(index)
//                        }
//                    })
//            }) {
//        if (view is View.AllApps) {
//            letters.forEach { letter ->
//                val isSelected = selectedLetter == letter
//                Text(
//                    text = letter.toString(),
//                    style = if (isSelected) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
//                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
//                )
//            }
//        }
//    }
//}

@Composable
fun LetterBar(
    letters: List<Char>,
    view: View,
    update: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {

    val slotFillFraction: Float = 0.85f; // fraction of slot to try to fill (0.65..0.85)
    val minTextSp = 5.sp;
    val maxTextSp = 32.sp;
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    var barHeightPx by remember { mutableStateOf(0) }
    var chosenSp by remember { mutableStateOf(24.sp) } // fallback

    // compute chosenSp whenever letters or size changes
    LaunchedEffect(letters, barHeightPx, slotFillFraction) {
        if (barHeightPx <= 0 || letters.isEmpty()) return@LaunchedEffect
        val n = letters.size
        val slotPx = barHeightPx.toFloat() / n.toFloat()

        // initial candidate in px -> convert to sp respecting density
        val candidatePx = slotPx * slotFillFraction
        val minPx = with(density) { minTextSp.toPx() }
        val maxPx = with(density) { maxTextSp.toPx() }
        chosenSp = with(density) { candidatePx.coerceIn(minPx, maxPx).toSp() }

        // clamp candidateSp to min/max in px
//        candidatePx = candidatePx.coerceIn(minPx, maxPx)
//        Log.d("LetterBar", "slot px: $slotPx")
//        Log.d("LetterBar", "initial candidate px: $candidatePx")
//        Log.d("LetterBar", "minPx: $minPx")
//        Log.d("LetterBar", "maxPx: $maxPx")

//        repeat(8) { // limited iterations
//            val candidateSpScaled = with(density) { candidatePx.toSp() }
//            val style = TextStyle(
//                fontSize = candidateSpScaled,
////                lineHeight = candidateSpScaled
////                lineHeightStyle = LineHeighttyle(
////                    alignment = LineHeightStyle.Alignment.Top,
////                    trim = LineHeightStyle.Trim.Both
////                )
//            )
//            val layout = measurer.measure(AnnotatedString("M"), style = style)
//            val measuredHeight = layout.size.height.toFloat()
//            Log.d("LetterBar", "measured height px: $measuredHeight")
//            Log.d("LetterBar", "fontScale=${density.fontScale}")
//            if (measuredHeight <= slotPx) return@LaunchedEffect run {
//                chosenSp = with(density) { candidatePx.toSp() }
//                val amount = barHeightPx / measuredHeight
//                Log.d("LetterBar", "amount calc: $amount")
//                Log.d("LetterBar", "amount letters: ${letters.size}")
//                Log.d("LetterBar", "chosenSp in return: $chosenSp")
//                Log.d("LetterBar", "chosenPx in return: $candidatePx")
//            }
//            // shrink: reduce candidate by proportion of overflow but keep it geometric
//            val ratio = (slotPx / measuredHeight).coerceIn(0.5f, 0.95f)
//            Log.d("LetterBar", "NEVER CALLED: $ratio")
//            candidatePx *= ratio
//            candidatePx = candidatePx.coerceAtLeast(minPx)
//        }

        // fallback if still too large
//        chosenSp = with(density) { candidatePx.toSp() }
//        Log.d("LetterBar", "fallback chosenSp: $chosenSp")
    }
    LaunchedEffect(chosenSp) {
        Log.d("LetterBar", "chosenSp: $chosenSp")
    }

    var isTouched by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<Char?>(null) }
    var coords: LayoutCoordinates? = null

    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp)
            .padding(bottom = 1f / 8f * LocalConfiguration.current.screenHeightDp.dp)
            .fillMaxHeight()
            .width(if (isTouched) (2 * H_PAD2).dp else H_PAD2.dp)
            .onGloballyPositioned { layoutCoordinates ->
                coords = layoutCoordinates
                barHeightPx = layoutCoordinates.size.height
            }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { isTouched = true },
                    onDragEnd = { isTouched = false; selectedLetter = null },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val localY =
                            change.position.y.coerceIn(0f, ((coords?.size?.height ?: 1).toFloat()))
                        val h = coords?.size?.height?.toFloat() ?: 1f
                        val idx =
                            ((localY / h) * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        selectedLetter = letters[idx]
                        update(idx)
                    })
            }
    ) {
        if (view is View.AllApps) {
            val style = TextStyle(
                fontSize = chosenSp,
//                lineHeight = 0.sp,
//                lineHeightStyle = LineHeightStyle(
//                    alignment = LineHeightStyle.Alignment.Top,
//                    trim = LineHeightStyle.Trim.Both
//                )
            )
            letters.forEach { letter ->
                Text(
                    text = letter.toString(),
                    style = style,
//                    lineHeight = 1f,
//                    lineHeeightStyle = LineHeightStyle(
//                        alignment = LineHeightStyle.Alignment.Top,
//                        trim = LineHeightStyle.Trim.Both
//                    ),
                    color = if (letter == selectedLetter) Color.White else Color.Red,
                    modifier = Modifier.wrapContentSize(Alignment.Center)
//                        .border(1.dp, Color.Red)
                )
            }
        }
    }
    val h = with(density) { barHeightPx.toDp() }
    Box(
        modifier = Modifier
            .padding(top = 1f / 3f * LocalConfiguration.current.screenHeightDp.dp)
            .height(h)
            .width(5.dp)
            .background(Color.Red)
    )
}


@Composable
fun IconRow(uiRow: UiRow, appVM: AppsVM, viewVM: ViewVM, modifier: Modifier = Modifier) {
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
    color = MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.headlineMedium.copy(
        shadow = Shadow(
            color = MaterialTheme.colorScheme.surface,
            offset = Offset(0.01f, 0.01f),
            blurRadius = 5f
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
fun ShortcutPopup(state: MenuState.Popup, appsVM: AppsVM, viewVM: ViewVM) {
    val haptic = LocalHapticFeedback.current
    val entries by produceState(initialValue = emptyList(), state.item) {
        value = appsVM.popupEntries(state.item)
    }
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
            val height = 58.dp * entries.size // icon is 42dp and padding is 2 * 8dp
            Column(
                modifier = Modifier
                    .offset(
                        x = H_PAD.dp, y = (yDp - height - safeTopDp).coerceAtLeast(0.dp)
                    )
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
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