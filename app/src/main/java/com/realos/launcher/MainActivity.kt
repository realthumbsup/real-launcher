@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.realos.launcher

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : ComponentActivity() {
    private val homeSignal = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val tilt = rememberTilt()
                CompositionLocalProvider(LocalTilt provides tilt) { RealOSLauncher(homeSignal) }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        homeSignal.value++
    }
}

enum class LauncherTab { HOME, ALL_APPS, CALENDAR, WEATHER }
enum class PermState { INITIAL, ASKING, GRANTED, DENIED }

val GlassBg = Color(0x40FFFFFF)
val GlassBorder = Color(0x66FFFFFF)
val DarkGlassBg = Color(0x801A1A1A)

val LocalTilt = compositionLocalOf<State<Offset>> { mutableStateOf(Offset.Zero) }

@Composable
fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = (event.values[0] / 9.81f).coerceIn(-1f, 1f)
                val y = (event.values[1] / 9.81f).coerceIn(-1f, 1f)
                val prev = tilt.value
                tilt.value = Offset(prev.x * 0.85f + x * 0.15f, prev.y * 0.85f + y * 0.15f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

fun Modifier.glass(shape: Shape): Modifier = composed {
    val tiltState = LocalTilt.current
    this
        .clip(shape)
        .drawWithContent {
            val t = tiltState.value
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.38f),
                        Color(0xFFBFD8FF).copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.16f)
                    ),
                    start = Offset(size.width * (0.5f - t.x * 0.4f), 0f),
                    end = Offset(size.width * (0.5f + t.x * 0.4f), size.height)
                )
            )
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            val cx = size.width * (0.3f + (t.x + 1f) * 0.2f)
            val cy = size.height * (0.2f + (t.y + 1f) * 0.2f)
            drawOutline(
                outline = outline,
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 1f),
                        Color.White.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.60f)
                    ),
                    center = Offset(cx, cy),
                    radius = size.maxDimension * 0.7f
                ),
                style = Stroke(width = 2.dp.toPx())
            )
        }
}

fun launchApp(context: Context, packageName: String) {
    context.packageManager.getLaunchIntentForPackage(packageName)?.let { context.startActivity(it) }
}

fun currentMonthName(): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())

fun wallpaperPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun loadWallpaper(context: Context): BitmapPainter? = try {
    WallpaperManager.getInstance(context).drawable?.let { drawable ->
        BitmapPainter(drawable.toBitmap(720, 1280).asImageBitmap())
    }
} catch (e: Throwable) {
    null
}

@Composable
fun RealOSLauncher(homeSignal: MutableState<Int>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var wallpaperPainter by remember { mutableStateOf<BitmapPainter?>(null) }
    var wallpaperAsked by remember { mutableStateOf(false) }
    val wallpaperPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wallpaperPainter = loadWallpaper(context)
    }
    LaunchedEffect(Unit) {
        if (!wallpaperAsked) {
            wallpaperAsked = true
            val perm = wallpaperPermission()
            if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                wallpaperPainter = loadWallpaper(context)
            } else {
                wallpaperPermissionLauncher.launch(perm)
            }
        }
    }

    var currentTab by remember { mutableStateOf(LauncherTab.HOME) }
    var showSearchOverlay by remember { mutableStateOf(false) }
    var sheetExpanded by remember { mutableStateOf(false) }

    val allApps = remember { getInstalledApps(context) }
    val favoriteApps = remember { allApps.take(4) }

    var currentTime by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()); delay(1000) } }
    val currentDate = remember { SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.getDefault()).format(Date()) }

    var weatherTemp by remember { mutableStateOf<Double?>(null) }
    var weatherCode by remember { mutableStateOf<Int?>(null) }
    var weatherLocation by remember { mutableStateOf("Locating...") }
    var weatherLoading by remember { mutableStateOf(true) }
    var activeAlerts by remember { mutableStateOf<List<WeatherAlert>>(emptyList()) }
    var locationPermState by remember { mutableStateOf(PermState.INITIAL) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            locationPermState = PermState.GRANTED; weatherLoading = true
            scope.launch { fetchWeather(context) { t, h, c, w, loc, alerts -> weatherTemp = t; weatherCode = c; weatherLocation = loc; weatherLoading = false; activeAlerts = alerts } }
        } else { locationPermState = PermState.DENIED; weatherLoading = false }
    }

    LaunchedEffect(currentTab) {
        if (currentTab == LauncherTab.WEATHER) {
            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                if (locationPermState != PermState.GRANTED) {
                    locationPermState = PermState.GRANTED; weatherLoading = true
                    scope.launch { fetchWeather(context) { t, h, c, w, loc, alerts -> weatherTemp = t; weatherCode = c; weatherLocation = loc; weatherLoading = false; activeAlerts = alerts } }
                }
            } else if (locationPermState == PermState.INITIAL) {
                locationPermState = PermState.ASKING
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    LaunchedEffect(homeSignal.value) {
        if (homeSignal.value > 0) {
            currentTab = if (currentTab == LauncherTab.ALL_APPS) LauncherTab.HOME else LauncherTab.ALL_APPS
            showSearchOverlay = false
            sheetExpanded = false
        }
    }

    BackHandler(enabled = showSearchOverlay || sheetExpanded || currentTab != LauncherTab.HOME) {
        when {
            showSearchOverlay -> showSearchOverlay = false
            sheetExpanded -> sheetExpanded = false
            else -> currentTab = LauncherTab.HOME
        }
    }

    val onTab: (LauncherTab) -> Unit = { tab ->
        currentTab = if (tab == LauncherTab.ALL_APPS && currentTab == LauncherTab.ALL_APPS) LauncherTab.HOME else tab
        sheetExpanded = false
    }

    Box(Modifier.fillMaxSize()) {
        val wp = wallpaperPainter
        if (wp != null) {
            Image(painter = wp, contentDescription = "Wallpaper", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)))
        }

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    LauncherTab.HOME -> DesktopView(allApps, favoriteApps, { app -> launchApp(context, app.packageName) }) { showSearchOverlay = true }
                    LauncherTab.ALL_APPS -> AllAppsView(allApps) { app -> launchApp(context, app.packageName) }
                    LauncherTab.CALENDAR -> CalendarView()
                    LauncherTab.WEATHER -> WeatherView(weatherTemp, weatherCode, weatherLocation, weatherLoading, locationPermState, { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
                }

                if (sheetExpanded) {
                    SwipeUpSheet(
                        clockTime = currentTime,
                        dateStr = currentDate,
                        weatherTemp = weatherTemp,
                        weatherCode = weatherCode,
                        location = weatherLocation,
                        alerts = activeAlerts
                    )
                }
            }

            BottomDock(
                currentTab = currentTab,
                sheetExpanded = sheetExpanded,
                onSheetChange = { sheetExpanded = it },
                onTab = onTab,
                onSearch = { showSearchOverlay = true }
            )
        }

        if (showSearchOverlay) SearchOverlay(allApps) { showSearchOverlay = false }
    }
}

val DockGrey = Color(0xFFB8BFC9)

fun Modifier.sunGlass(shape: Shape): Modifier = composed {
    val tiltState = LocalTilt.current
    this
        .clip(shape)
        .drawWithContent {
            val t = tiltState.value

            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.34f),
                        Color(0xFFBFD8FF).copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.20f)
                    )
                )
            )

            val sunCenter = Offset(size.width * (0.5f + t.x * 0.4f), size.height * (0.3f + t.y * 0.3f))
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFF8E1).copy(alpha = 0.75f),
                        Color(0xFFFFF8E1).copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = sunCenter,
                    radius = size.maxDimension * 0.45f
                ),
                blendMode = BlendMode.Screen
            )

            val sheenX = size.width * (0.5f + t.x * 0.35f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                    startX = sheenX - size.width * 0.15f,
                    endX = sheenX + size.width * 0.15f
                )
            )

            drawContent()

            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.White,
                        Color(0xFF9FB8E0),
                        Color.White,
                        Color(0xFFBFD8FF),
                        Color.White
                    ),
                    center = sunCenter
                ),
                style = Stroke(width = 2.dp.toPx())
            )
        }
}

@Composable
fun BottomDock(currentTab: LauncherTab, sheetExpanded: Boolean, onSheetChange: (Boolean) -> Unit, onTab: (LauncherTab) -> Unit, onSearch: () -> Unit) {
    var dragAccum by remember { mutableStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(78.dp)
            .sunGlass(RoundedCornerShape(30.dp))
            .pointerInput(sheetExpanded) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, drag -> change.consume(); dragAccum += drag },
                    onDragEnd = {
                        if (dragAccum < -40f && !sheetExpanded) onSheetChange(true)
                        else if (dragAccum > 40f && sheetExpanded) onSheetChange(false)
                        dragAccum = 0f
                    }
                )
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DockIcon("Calendar", Icons.Default.DateRange) { onTab(LauncherTab.CALENDAR) }
        DockIcon("Home", Icons.Default.Home) { onTab(LauncherTab.ALL_APPS) }
        DockSearch(Modifier.weight(1f)) { onSearch() }
        DockIcon("Weather", Icons.Default.Cloud) { onTab(LauncherTab.WEATHER) }
    }
}

@Composable
fun DockIcon(label: String, icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(imageVector = icon, contentDescription = label, tint = DockGrey, modifier = Modifier.size(26.dp))
    }
}

@Composable
fun DockSearch(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = "Search", tint = DockGrey, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Search", color = DockGrey, fontSize = 14.sp)
    }
}

@Composable
fun BoxScope.SwipeUpSheet(clockTime: String, dateStr: String, weatherTemp: Double?, weatherCode: Int?, location: String, alerts: List<WeatherAlert>) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .glass(RoundedCornerShape(28.dp))
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(clockTime, fontSize = 56.sp, fontWeight = FontWeight.Light, color = Color.White)
        Text(dateStr, fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(getWeatherEmoji(weatherCode ?: 0), fontSize = 40.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${weatherTemp?.toInt() ?: "--"}°C", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("${getWeatherDesc(weatherCode ?: 0)} • $location", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(currentMonthName(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        MonthGrid(compact = true)
        Spacer(Modifier.height(12.dp))
        alerts.forEach { alert ->
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFB71C1C), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(alert.headline, color = Color.White, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
fun MonthGrid(compact: Boolean) {
    val today = Calendar.getInstance()
    val currentDay = today.get(Calendar.DAY_OF_MONTH)
    val daysInMonth = today.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDay = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1
    val leading: List<Int?> = List(firstDay) { null }
    val cells: List<Int?> = leading + (1..daysInMonth).map { it }
    val textSize = if (compact) 11.sp else 16.sp
    val dot = if (compact) 22.dp else 34.dp

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = textSize, fontWeight = FontWeight.Bold, color = Color(0xFF0A84FF))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            if (day == currentDay) {
                                Box(Modifier.size(dot).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                                    Text("$day", fontSize = textSize, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text("$day", fontSize = textSize, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppIcon(app: AppInfo, size: Dp, onClick: () -> Unit) {
    Image(
        bitmap = app.icon.toBitmap(128, 128).asImageBitmap(),
        contentDescription = app.label,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).clickable(onClick = onClick)
    )
}

@Composable
fun AppTile(app: AppInfo, iconSize: Dp, labelSize: TextUnit, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(iconSize + 16.dp).padding(6.dp)
    ) {
        AppIcon(app, iconSize, onClick)
        Spacer(Modifier.height(4.dp))
        Text(app.label, color = Color.White, fontSize = labelSize, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun DesktopView(allApps: List<AppInfo>, favoriteApps: List<AppInfo>, onLaunch: (AppInfo) -> Unit, onSearchClick: () -> Unit) {
    val topApps = allApps.take(4)
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(16.dp).width(200.dp)) {
            topApps.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { app -> AppTile(app, 56.dp, 11.sp) { onLaunch(app) } }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .width(80.dp)
                .sunGlass(RoundedCornerShape(36.dp))
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            favoriteApps.forEach { app -> AppIcon(app, 52.dp) { onLaunch(app) } }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .fillMaxWidth()
                .sunGlass(RoundedCornerShape(28.dp))
                .padding(14.dp)
        ) {
            SearchPill(onClick = onSearchClick)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                favoriteApps.forEach { app -> AppIcon(app, 48.dp) { onLaunch(app) } }
            }
        }
    }
}

@Composable
fun SearchPill(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Search apps...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
fun SearchOverlay(allApps: List<AppInfo>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val filtered = allApps.filter { it.label.contains(query, ignoreCase = true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .sunGlass(RoundedCornerShape(28.dp))
                .padding(16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Search apps...", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
                        inner()
                    }
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
            }
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f)) {
                gridItems(filtered, key = { it.packageName }) { app ->
                    AppTile(app, 60.dp, 11.sp) {
                        launchApp(context, app.packageName)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
fun AllAppsView(allApps: List<AppInfo>, onLaunch: (AppInfo) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .glass(RoundedCornerShape(28.dp))
            .padding(16.dp)
    ) {
        Text("All Apps", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(allApps, key = { it.packageName }) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onLaunch(app) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = app.icon.toBitmap(128, 128).asImageBitmap(),
                        contentDescription = app.label,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(app.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun CalendarView() {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(24.dp)) {
            Text(currentMonthName(), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(16.dp))
            MonthGrid(compact = false)
        }
    }
}

@Composable
fun WeatherView(temp: Double?, code: Int?, location: String, loading: Boolean, permState: PermState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (permState == PermState.DENIED) {
                Text("📍", fontSize = 64.sp); Spacer(Modifier.height(16.dp))
                Text("Permission for location not granted", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center); Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D7))) { Text("Grant Permission") }
            } else if (loading) {
                CircularProgressIndicator(color = Color(0xFF0078D7))
            } else {
                Text(location, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Color.White); Spacer(Modifier.height(16.dp))
                Text(getWeatherEmoji(code ?: 0), fontSize = 100.sp); Spacer(Modifier.height(16.dp))
                Text("${temp?.toInt() ?: "--"}°C", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(getWeatherDesc(code ?: 0), fontSize = 28.sp, color = Color.Gray)
            }
        }
    }
}

data class WeatherResponse(val current: CurrentWeather)
data class CurrentWeather(val temperature_2m: Double, val relative_humidity_2m: Int, val apparent_temperature: Double, val weather_code: Int, val wind_speed_10m: Double, val wind_gusts_10m: Double? = null)
data class WeatherAlert(val event: String, val severity: String, val headline: String)
interface WeatherApi { @GET("v1/forecast") suspend fun getWeather(@Query("latitude") lat: Double, @Query("longitude") lon: Double, @Query("current") current: String = "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m"): WeatherResponse }
fun getWeatherEmoji(code: Int): String = when (code) { 0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"; 51, 53, 55 -> "🌦️"; 61, 63, 65 -> "🌧️"; 71, 73, 75 -> "❄️"; 80, 81, 82 -> "🌧️"; 95, 96, 99 -> "⛈️"; else -> "🌡️" }
fun getWeatherDesc(code: Int): String = when (code) { 0 -> "Clear Sky"; 1, 2 -> "Partly Cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 61, 63, 65 -> "Rain"; 71, 73, 75 -> "Snow"; 80, 81, 82 -> "Rain Showers"; 95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with Hail"; else -> "Unknown" }
fun generateGlobalAlerts(weather: CurrentWeather): List<WeatherAlert> { val alerts = mutableListOf<WeatherAlert>(); if (weather.weather_code == 95) alerts.add(WeatherAlert("Thunderstorm", "Severe", "⚠️ Strong thunderstorms. Stay indoors.")); if (weather.weather_code == 96 || weather.weather_code == 99) alerts.add(WeatherAlert("Hail Warning", "Extreme", "⚠️ Severe hail. Seek shelter immediately.")); if ((weather.wind_gusts_10m ?: 0.0) > 80.0) alerts.add(WeatherAlert("High Wind", "Severe", "⚠️ Damaging wind gusts.")); return alerts }

suspend fun fetchWeather(context: Context, onResult: (Double?, Int?, Int?, Double?, String, List<WeatherAlert>) -> Unit) {
    try {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val location = withContext(Dispatchers.IO) { fusedLocationClient.lastLocation.await() }
        val lat = location?.latitude ?: 52.52; val lon = location?.longitude ?: 13.40
        val geocoder = Geocoder(context, Locale.getDefault()); val addresses = withContext(Dispatchers.IO) { @Suppress("DEPRECATION") geocoder.getFromLocation(lat, lon, 1) }
        val address = addresses?.firstOrNull(); val city = address?.locality ?: address?.subAdminArea ?: "Unknown"; val country = address?.countryName ?: ""
        val locationName = if (country.isNotEmpty() && country != city) "$city, $country" else city
        val retrofit = Retrofit.Builder().baseUrl("https://api.open-meteo.com/").addConverterFactory(GsonConverterFactory.create()).build()
        val api = retrofit.create(WeatherApi::class.java); val weatherResponse = withContext(Dispatchers.IO) { api.getWeather(lat, lon) }
        onResult(weatherResponse.current.temperature_2m, weatherResponse.current.relative_humidity_2m, weatherResponse.current.weather_code, weatherResponse.current.wind_speed_10m, locationName, generateGlobalAlerts(weatherResponse.current))
    } catch (e: Exception) { onResult(null, null, null, null, "Weather Error", emptyList()) }
}
