@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.realos.launcher

import android.app.ActivityOptions
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RealOSHomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RealOSHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    val bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

    var isEditMode by remember { mutableStateOf(false) }
    var columns by remember { mutableStateOf(4) }
    var is24HourFormat by remember { mutableStateOf(false) } // Settings Toggle
    var currentTime by remember { mutableStateOf("") }

    val installedApps = remember { getInstalledApps(context) }
    val appList = remember { mutableStateListOf<AppInfo>().apply { addAll(installedApps) } }

    // Update clock every minute
    LaunchedEffect(is24HourFormat) {
        while (true) {
            val format = if (is24HourFormat) "HH:mm" else "hh:mm a"
            currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(60000L)
        }
    }

    val currentDate = remember { SimpleDateFormat("d. MMMM yyyy", Locale.getDefault()).format(Date()) }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 0.dp, // Hidden until swiped up
        sheetContainerColor = Color(0xFF1E1E1E),
        sheetContent = {
            // THE SWIPE UP DASHBOARD
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = currentTime, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
                    IconButton(onClick = { is24HourFormat = !is24HourFormat }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
                    }
                }
                Text(text = currentDate, color = Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "☀️", fontSize = 32.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "9°C", color = Color.White, fontSize = 32.sp)
                }
            }
        }
    ) { padding ->
        Scaffold(
            modifier = Modifier.padding(padding),
            bottomBar = {
                RealOSBottomBar(
                    pagerState = pagerState,
                    scope = scope,
                    isEditMode = isEditMode,
                    onLogoClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    onLogoLongClick = { isEditMode = !isEditMode }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.White)
                    .pointerInput(Unit) {
                        // LONG PRESS EMPTY SPACE TO RESIZE / ENTER EDIT MODE
                        detectTapGestures(
                            onLongPress = { isEditMode = true }
                        )
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isEditMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Edit Mode: Drag Apps", color = Color.Gray, fontSize = 14.sp)
                            Button(onClick = { if (columns < 6) columns++ else columns = 3 }) {
                                Text("Grid: $columns cols")
                            }
                        }
                    }

                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> CalendarTabContent()
                            1 -> HomeTabContent(
                                appList = appList,
                                isEditMode = isEditMode,
                                columns = columns
                            )
                            2 -> WeatherTabContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RealOSBottomBar(
    pagerState: androidx.compose.foundation.pager.PagerState,
    scope: kotlinx.coroutines.CoroutineScope,
    isEditMode: Boolean,
    onLogoClick: () -> Unit,
    onLogoLongClick: () -> Unit
) {
    Surface(
        color = Color(0xFFF5F5F5),
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(0) } }) {
                Icon(Icons.Default.DateRange, contentDescription = "Calendar", tint = if(pagerState.currentPage == 0) Color(0xFF42A5F5) else Color.Gray)
            }

            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (isEditMode) Color(0xFF42A5F5).copy(alpha = 0.2f) else Color.Transparent)
                    .combinedClickable(onClick = onLogoClick, onLongClick = onLogoLongClick),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.real_os_logo),
                    contentDescription = "Real OS Logo",
                    modifier = Modifier.size(48.dp)
                )
            }

            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(2) } }) {
                Icon(Icons.Default.WbSunny, contentDescription = "Weather", tint = if(pagerState.currentPage == 2) Color(0xFF42A5F5) else Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTabContent(
    appList: MutableList<AppInfo>,
    isEditMode: Boolean,
    columns: Int
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(appList, key = { _, app -> app.packageName }) { index, app ->
            AppIconItem(
                app = app,
                isEditMode = isEditMode,
                onLaunch = { view ->
                    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) {
                        val options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()
                        context.startActivity(intent, options)
                    }
                },
                onMove = { fromIndex, toIndex ->
                    if (toIndex in appList.indices) {
                        val item = appList.removeAt(fromIndex)
                        appList.add(toIndex, item)
                    }
                },
                index = index
            )
        }
    }
}

@Composable
fun AppIconItem(
    app: AppInfo,
    isEditMode: Boolean,
    onLaunch: (android.view.View) -> Unit,
    onMove: (Int, Int) -> Unit,
    index: Int
) {
    val view = LocalView.current
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            // FLOATING EFFECT: zIndex and shadow when dragging
            .zIndex(if (isDragging) 1f else 0f)
            .offset(x = offsetX.dp, y = offsetY.dp)
            .shadow(if (isDragging) 12.dp else 0.dp, RoundedCornerShape(12.dp))
            .background(if (isDragging) Color.White.copy(alpha = 0.8f) else Color.Transparent)
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { isDragging = true },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x / density
                            offsetY += dragAmount.y / density
                        },
                        onDragEnd = {
                            isDragging = false
                            val colShift = (offsetX / 80).toInt()
                            val rowShift = (offsetY / 100).toInt() * 4
                            val newIndex = index + colShift + rowShift
                            onMove(index, newIndex)
                            offsetX = 0f
                            offsetY = 0f
                        }
                    )
                } else {
                    detectTapGestures(onTap = { onLaunch(view) })
                }
            }
    ) {
        Text(
            text = app.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.DarkGray,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Image(
            bitmap = app.icon.toBitmap(100, 100).asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier.size(56.dp)
        )
    }
}

@Composable
fun CalendarTabContent() {
    val calendar = Calendar.getInstance()
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(calendar.time)
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = calendar.apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = monthName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                Text(text = it, fontWeight = FontWeight.Bold, color = Color.Gray)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(7)) {
            items(firstDayOfWeek) { Spacer(modifier = Modifier.height(1.dp)) }
            items(daysInMonth) { day ->
                Box(modifier = Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Text(text = "${day + 1}", fontSize = 16.sp, color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
fun WeatherTabContent() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFE3F2FD)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.WbSunny, contentDescription = "Sunny", modifier = Modifier.size(100.dp), tint = Color(0xFFFFB300))
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "9°C", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        Text(text = "Sunny", fontSize = 24.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = "Weather API integration coming soon", fontSize = 14.sp, color = Color.Gray)
    }
}
