@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.realos.launcher

import android.Manifest
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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

data class WeatherResponse(val current: CurrentWeather)
data class CurrentWeather(
    val temperature_2m: Double, val relative_humidity_2m: Int, val apparent_temperature: Double,
    val weather_code: Int, val wind_speed_10m: Double, val wind_gusts_10m: Double? = null
)
data class WeatherAlert(val event: String, val severity: String, val headline: String)

interface WeatherApi {
    @GET("v1/forecast")
    suspend fun getWeather(
        @Query("latitude") lat: Double, @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m"
    ): WeatherResponse
}

fun getWeatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"; 51, 53, 55 -> "🌦️"
    61, 63, 65 -> "🌧️"; 71, 73, 75 -> "❄️"; 80, 81, 82 -> "🌧️"; 95, 96, 99 -> "⛈️"; else -> "🌡️"
}

fun getWeatherDesc(code: Int): String = when (code) {
    0 -> "Clear Sky"; 1, 2 -> "Partly Cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"; 61, 63, 65 -> "Rain"; 71, 73, 75 -> "Snow"
    80, 81, 82 -> "Rain Showers"; 95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with Hail"; else -> "Unknown"
}

fun getMetroColor(packageName: String): Color {
    val colors = listOf(
        Color(0xFF0078D7), Color(0xFFD13438), Color(0xFF107C10), Color(0xFFFFB900),
        Color(0xFF881798), Color(0xFF00B7C3), Color(0xFFE3008C), Color(0xFF767676)
    )
    return colors[Math.abs(packageName.hashCode()) % colors.size]
}

fun generateGlobalAlerts(weather: CurrentWeather): List<WeatherAlert> {
    val alerts = mutableListOf<WeatherAlert>()
    if (weather.weather_code == 95) alerts.add(WeatherAlert("Thunderstorm", "Severe", "⚠️ Strong thunderstorms. Stay indoors."))
    if (weather.weather_code == 96 || weather.weather_code == 99) alerts.add(WeatherAlert("Hail Warning", "Extreme", "⚠️ Severe hail. Seek shelter immediately."))
    if ((weather.wind_gusts_10m ?: 0.0) > 80.0) alerts.add(WeatherAlert("High Wind", "Severe", "⚠️ Damaging wind gusts. Secure loose objects."))
    if (weather.temperature_2m > 40.0) alerts.add(WeatherAlert("Extreme Heat", "Severe", "⚠️ Dangerously high temperatures."))
    if (weather.temperature_2m < -5.0) alerts.add(WeatherAlert("Extreme Cold", "Severe", "⚠️ Dangerously low temperatures."))
    return alerts
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { RealOSLauncher() } }
    }
}

enum class LauncherTab { HOME, CALENDAR, WEATHER }
enum class PermState { INITIAL, ASKING, GRANTED, DENIED }

val GreyBg = Color(0xFF2B2B2B)
val CardBg = Color(0xFF1F1F1F)

@Composable
fun RealOSLauncher() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(LauncherTab.HOME) }
    var showAppDrawer by remember { mutableStateOf(false) }
    var sheetExpanded by remember { mutableStateOf(false) }
    var isDraggingAnyTile by remember { mutableStateOf(false) }

    val homeApps = remember { mutableStateListOf<AppInfo>() }
    val allApps = remember { getInstalledApps(context) }

    var columns by remember { mutableStateOf(3) }
    var isEditMode by remember { mutableStateOf(false) }

    var currentTime by remember { mutableStateOf("") }

    var weatherTemp by remember { mutableStateOf<Double?>(null) }
    var weatherHumidity by remember { mutableStateOf<Int?>(null) }
    var weatherCode by remember { mutableStateOf<Int?>(null) }
    var weatherWind by remember { mutableStateOf<Double?>(null) }
    var weatherLocation by remember { mutableStateOf("Locating...") }
    var weatherLoading by remember { mutableStateOf(true) }
    var activeAlerts by remember { mutableStateOf<List<WeatherAlert>>(emptyList()) }
    var extremeAlert by remember { mutableStateOf<WeatherAlert?>(null) }
    var locationPermState by remember { mutableStateOf(PermState.INITIAL) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }
    val currentDate = remember { SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.getDefault()).format(Date()) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationPermState = PermState.GRANTED
            weatherLoading = true
            scope.launch {
                fetchWeather(context) { t, h, c, w, loc, alerts ->
                    weatherTemp = t; weatherHumidity = h; weatherCode = c; weatherWind = w
                    weatherLocation = loc; activeAlerts = alerts; weatherLoading = false
                    extremeAlert = alerts.firstOrNull { it.severity == "Extreme" }
                }
            }
        } else {
            locationPermState = PermState.DENIED
            weatherLoading = false
        }
    }

    // Only request location when Weather tab is opened
    LaunchedEffect(currentTab, showAppDrawer) {
        if (currentTab == LauncherTab.WEATHER && !showAppDrawer) {
            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                if (locationPermState != PermState.GRANTED) {
                    locationPermState = PermState.GRANTED
                    weatherLoading = true
                    scope.launch {
                        fetchWeather(context) { t, h, c, w, loc, alerts ->
                            weatherTemp = t; weatherHumidity = h; weatherCode = c; weatherWind = w
                            weatherLocation = loc; activeAlerts = alerts; weatherLoading = false
                            extremeAlert = alerts.firstOrNull { it.severity == "Extreme" }
                        }
                    }
                }
            } else if (locationPermState == PermState.INITIAL) {
                locationPermState = PermState.ASKING
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else if (locationPermState == PermState.GRANTED) {
                locationPermState = PermState.DENIED
                weatherLoading = false
            }
        }
    }

    extremeAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = Color(0xFFB71C1C),
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("CRITICAL WARNING", color = Color.White, fontWeight = FontWeight.Bold) } },
            text = { Text(alert.headline, color = Color.White) },
            confirmButton = { TextButton(onClick = { extremeAlert = null }) { Text("I Understand", color = Color.White) } }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = GreyBg,
            bottomBar = {
                NavigationBar(containerColor = CardBg) {
                    NavigationBarItem(
                        selected = currentTab == LauncherTab.CALENDAR && !showAppDrawer,
                        onClick = { currentTab = LauncherTab.CALENDAR; showAppDrawer = false },
                        icon = { Icon(Icons.Default.DateRange, "Calendar") },
                        label = { Text("Calendar") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF0078D7), selectedTextColor = Color(0xFF0078D7), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray)
                    )
                    NavigationBarItem(
                        selected = currentTab == LauncherTab.HOME || showAppDrawer,
                        onClick = {
                            if (showAppDrawer) { showAppDrawer = false; currentTab = LauncherTab.HOME }
                            else { showAppDrawer = true }
                        },
                        icon = { Icon(Icons.Default.Home, "Home/Apps") },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF0078D7), selectedTextColor = Color(0xFF0078D7), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray)
                    )
                    NavigationBarItem(
                        selected = currentTab == LauncherTab.WEATHER && !showAppDrawer,
                        onClick = { currentTab = LauncherTab.WEATHER; showAppDrawer = false },
                        icon = { Icon(Icons.Default.Cloud, "Weather") },
                        label = { Text("Weather") },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF0078D7), selectedTextColor = Color(0xFF0078D7), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray)
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(GreyBg)) {
                when {
                    showAppDrawer -> AllAppsList(
                        allApps = allApps,
                        homeApps = homeApps,
                        onAddToHome = { app -> if (!homeApps.contains(app)) homeApps.add(app) }
                    )
                    currentTab == LauncherTab.CALENDAR -> CalendarView(clockTime = currentTime)
                    currentTab == LauncherTab.WEATHER -> WeatherView(
                        temp = weatherTemp, humidity = weatherHumidity, code = weatherCode,
                        wind = weatherWind, location = weatherLocation, alerts = activeAlerts, loading = weatherLoading,
                        permState = locationPermState,
                        onRetry = {
                            val activity = context as? androidx.activity.ComponentActivity
                            val canAsk = activity?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ?: true
                            if (!canAsk) {
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                locationPermState = PermState.ASKING
                                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        }
                    )
                    else -> MetroHome(
                        homeApps = homeApps, columns = columns, isEditMode = isEditMode,
                        onLongPressEmptySpace = { isEditMode = true },
                        onResize = { if (columns < 5) columns++ else columns = 2 },
                        onMove = { from, to -> if (to in homeApps.indices) { val item = homeApps.removeAt(from); homeApps.add(to, item) } },
                        onDragStateChanged = { isDraggingAnyTile = it }
                    )
                }
            }
        }

        if (isDraggingAnyTile) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 120.dp)) {
                Box(Modifier.size(56.dp).background(Color(0xFF0078D7), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowDownward, "Drop zone", tint = Color.White, modifier = Modifier.size(32.dp).rotate(-45f))
                }
            }
        }

        BottomSheetOverlay(
            expanded = sheetExpanded,
            onToggle = { sheetExpanded = !sheetExpanded },
            clockTime = currentTime,
            dateStr = currentDate,
            weatherTemp = weatherTemp,
            weatherCode = weatherCode,
            weatherDesc = getWeatherDesc(weatherCode ?: 0),
            weatherEmoji = getWeatherEmoji(weatherCode ?: 0),
            location = weatherLocation,
            alerts = activeAlerts
        )
    }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.BottomSheetOverlay(
    expanded: Boolean, onToggle: () -> Unit,
    clockTime: String, dateStr: String,
    weatherTemp: Double?, weatherCode: Int?,
    weatherDesc: String, weatherEmoji: String,
    location: String, alerts: List<WeatherAlert>
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val collapsedH = 40.dp
    val expandedH = screenH * 0.75f
    val height by animateDpAsState(if (expanded) expandedH else collapsedH, tween(280), label = "sheetH")
    val bottomPad by animateDpAsState(if (expanded) 0.dp else 80.dp, tween(280), label = "sheetPad")
    var dragAccum by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = bottomPad)
            .height(height)
            .background(CardBg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, drag -> change.consume(); dragAccum += drag },
                    onDragEnd = {
                        if (dragAccum < -40f && !expanded) onToggle()
                        else if (dragAccum > 40f && expanded) onToggle()
                        dragAccum = 0f
                    }
                )
            }
            .pointerInput(Unit) { detectTapGestures { onToggle() } }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Swipe",
                    tint = Color(0xFF0078D7),
                    modifier = Modifier.size(28.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(clockTime, fontSize = 56.sp, fontWeight = FontWeight.Light, color = Color.White)
                Text(dateStr, fontSize = 16.sp, color = Color.Gray)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(weatherEmoji, fontSize = 40.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${weatherTemp?.toInt() ?: "--"}°C", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("$weatherDesc • $location", fontSize = 13.sp, color = Color.Gray)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("This month", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MiniCalendar()
                Spacer(Modifier.height(12.dp))
                alerts.forEach { alert ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFFB71C1C), RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(alert.headline, color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun MiniCalendar() {
    val cal = Calendar.getInstance()
    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDay = cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1

    Column(Modifier.fillMaxWidth()) {
        Text(monthName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("S","M","T","W","T","F","S").forEach { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0078D7)) }
        }
        Spacer(Modifier.height(4.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.heightIn(max = 200.dp)) {
            items(firstDay) { Spacer(Modifier.size(1.dp)) }
            items(daysInMonth) { idx ->
                val day = idx + 1
                val isToday = day == currentDay
                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (isToday) {
                        Box(Modifier.size(24.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                            Text("$day", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("$day", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun MetroHome(
    homeApps: MutableList<AppInfo>, columns: Int, isEditMode: Boolean,
    onLongPressEmptySpace: () -> Unit, onResize: () -> Unit, onMove: (Int, Int) -> Unit,
    onDragStateChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(GreyBg).pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressEmptySpace() }) }
    ) {
        if (isEditMode) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Edit Mode: Drag tiles, resize grid", color = Color.Gray, fontSize = 13.sp)
                Button(onClick = onResize, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D7))) { Text("Grid: $columns Cols") }
            }
        }
        if (homeApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Press Home at the bottom to add apps", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(homeApps, key = { _, app -> app.packageName }) { index, app ->
                    MetroTile(app, isEditMode, index, columns, onMove, onDragStateChanged)
                }
            }
        }
    }
}

@Composable
fun MetroTile(app: AppInfo, isEditMode: Boolean, index: Int, columns: Int, onMove: (Int, Int) -> Unit, onDragStateChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isLaunching by remember { mutableStateOf(false) }

    val launchRotation by animateFloatAsState(if (isLaunching) 360f else 0f, tween(500, easing = FastOutSlowInEasing), label = "rot")
    val launchScale by animateFloatAsState(if (isLaunching) 4f else 1f, tween(500, easing = FastOutSlowInEasing), label = "scale")

    LaunchedEffect(isDragging) { onDragStateChanged(isDragging) }

    LaunchedEffect(isLaunching) {
        if (isLaunching) {
            delay(500)
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            if (intent != null) {
                context.startActivity(intent, ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle())
            }
            isLaunching = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            .zIndex(if (isDragging || isLaunching) 10f else 0f)
            .offset(x = offsetX.dp, y = offsetY.dp)
            .graphicsLayer {
                rotationY = launchRotation
                scaleX = launchScale
                scaleY = launchScale
                cameraDistance = 12f * density
            }
            .background(if (isDragging) Color(0xFF444444) else getMetroColor(app.packageName), RoundedCornerShape(4.dp))
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { isDragging = true },
                        onDrag = { change, drag -> change.consume(); offsetX += drag.x / density; offsetY += drag.y / density },
                        onDragEnd = {
                            isDragging = false
                            val colShift = (offsetX / 80).toInt(); val rowShift = (offsetY / 100).toInt() * columns
                            onMove(index, index + colShift + rowShift)
                            offsetX = 0f; offsetY = 0f
                        }
                    )
                } else {
                    detectTapGestures(
                        onTap = { isLaunching = true },
                        onLongPress = { isDragging = true }
                    )
                }
            }.padding(8.dp)
    ) {
        val tileWidth = maxWidth
        val iconSize = tileWidth * 0.40f
        val textSize = (tileWidth.value * 0.14f).coerceIn(10f, 20f).sp
        Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(iconSize).align(Alignment.Center))
        Text(app.label, fontSize = textSize, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.align(Alignment.BottomStart))
    }
}

@Composable
fun AllAppsList(allApps: List<AppInfo>, homeApps: List<AppInfo>, onAddToHome: (AppInfo) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    LazyColumn(modifier = Modifier.fillMaxSize().background(GreyBg), contentPadding = PaddingValues(16.dp)) {
        item { Text("All Apps", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp)) }
        items(allApps, key = { it.packageName }) { app ->
            val isAlreadyOnHome = homeApps.contains(app)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (intent != null) context.startActivity(intent, ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle())
                        },
                        onLongClick = { if (!isAlreadyOnHome) onAddToHome(app) }
                    ).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                Text(app.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isAlreadyOnHome) Icon(Icons.Default.Check, "Added", tint = Color(0xFF0078D7))
            }
        }
    }
}

@Composable
fun CalendarView(clockTime: String) {
    val cal = Calendar.getInstance()
    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDay = cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1

    Column(Modifier.fillMaxSize().background(GreyBg).padding(16.dp)) {
        Text(clockTime, fontSize = 48.sp, fontWeight = FontWeight.Light, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text(monthName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("S","M","T","W","T","F","S").forEach { Text(it, fontWeight = FontWeight.Bold, color = Color(0xFF0078D7)) }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(7)) {
            items(firstDay) { Spacer(Modifier.size(1.dp)) }
            items(daysInMonth) { idx ->
                val day = idx + 1
                val isToday = day == currentDay
                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (isToday) {
                        Box(Modifier.size(36.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) {
                            Text("$day", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("$day", fontSize = 16.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherView(
    temp: Double?, humidity: Int?, code: Int?, wind: Double?, location: String,
    alerts: List<WeatherAlert>, loading: Boolean,
    permState: PermState, onRetry: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(GreyBg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (permState == PermState.INITIAL || permState == PermState.ASKING) {
            Spacer(Modifier.height(100.dp))
            CircularProgressIndicator(color = Color(0xFF0078D7))
            Spacer(Modifier.height(16.dp))
            Text("Requesting location permission...", color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Text("Grant Real Launcher access to your location to see accurate weather.",
                color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else if (permState == PermState.DENIED) {
            Spacer(Modifier.height(80.dp))
            Text("📍", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text("Permission for location not granted",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Real Launcher needs your location to show weather for your area.",
                color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D7))) {
                Text("Grant Location Permission")
            }
        } else if (loading) {
            Spacer(Modifier.height(100.dp))
            CircularProgressIndicator(color = Color(0xFF0078D7))
            Spacer(Modifier.height(16.dp))
            Text("Fetching weather...", color = Color.Gray)
        } else {
            Spacer(Modifier.height(48.dp))
            Text(location, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(getWeatherEmoji(code ?: 0), fontSize = 100.sp)
            Spacer(Modifier.height(16.dp))
            Text("${temp?.toInt() ?: "--"}°C", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(getWeatherDesc(code ?: 0), fontSize = 28.sp, color = Color.Gray)
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💧 Humidity", fontSize = 16.sp, color = Color.Gray)
                    Text("${humidity ?: "--"}%", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💨 Wind", fontSize = 16.sp, color = Color.Gray)
                    Text("${wind?.toInt() ?: "--"} km/h", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            alerts.forEach { alert ->
                Row(Modifier.fillMaxWidth().background(Color(0xFFB71C1C), RoundedCornerShape(8.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(alert.headline, color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

suspend fun fetchWeather(context: Context, onResult: (Double?, Int?, Int?, Double?, String, List<WeatherAlert>) -> Unit) {
    try {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val location = withContext(Dispatchers.IO) { fusedLocationClient.lastLocation.await() }
        val lat = location?.latitude ?: 52.52
        val lon = location?.longitude ?: 13.40
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = withContext(Dispatchers.IO) { @Suppress("DEPRECATION") geocoder.getFromLocation(lat, lon, 1) }
        val address = addresses?.firstOrNull()
        val city = address?.locality ?: address?.subAdminArea ?: "Unknown"
        val country = address?.countryName ?: ""
        val locationName = if (country.isNotEmpty() && country != city) "$city, $country" else city

        val retrofit = Retrofit.Builder().baseUrl("https://api.open-meteo.com/").addConverterFactory(GsonConverterFactory.create()).build()
        val api = retrofit.create(WeatherApi::class.java)
        val weatherResponse = withContext(Dispatchers.IO) { api.getWeather(lat, lon) }

        onResult(weatherResponse.current.temperature_2m, weatherResponse.current.relative_humidity_2m, weatherResponse.current.weather_code, weatherResponse.current.wind_speed_10m, locationName, generateGlobalAlerts(weatherResponse.current))
    } catch (e: Exception) { onResult(null, null, null, null, "Weather Error", emptyList()) }
}
