@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.realos.launcher

import android.Manifest
import android.app.ActivityOptions
import android.app.WallpaperManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

data class WeatherResponse(val current: CurrentWeather)
data class CurrentWeather(val temperature_2m: Double, val relative_humidity_2m: Int, val apparent_temperature: Double, val weather_code: Int, val wind_speed_10m: Double, val wind_gusts_10m: Double? = null)
data class WeatherAlert(val event: String, val severity: String, val headline: String)
interface WeatherApi { @GET("v1/forecast") suspend fun getWeather(@Query("latitude") lat: Double, @Query("longitude") lon: Double, @Query("current") current: String = "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m"): WeatherResponse }
fun getWeatherEmoji(code: Int): String = when (code) { 0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"; 51, 53, 55 -> "🌦️"; 61, 63, 65 -> "🌧️"; 71, 73, 75 -> "❄️"; 80, 81, 82 -> "🌧️"; 95, 96, 99 -> "⛈️"; else -> "🌡️" }
fun getWeatherDesc(code: Int): String = when (code) { 0 -> "Clear Sky"; 1, 2 -> "Partly Cloudy"; 3 -> "Overcast"; 45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 61, 63, 65 -> "Rain"; 71, 73, 75 -> "Snow"; 80, 81, 82 -> "Rain Showers"; 95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with Hail"; else -> "Unknown" }
fun generateGlobalAlerts(weather: CurrentWeather): List<WeatherAlert> { val alerts = mutableListOf<WeatherAlert>(); if (weather.weather_code == 95) alerts.add(WeatherAlert("Thunderstorm", "Severe", "⚠️ Strong thunderstorms. Stay indoors.")); if (weather.weather_code == 96 || weather.weather_code == 99) alerts.add(WeatherAlert("Hail Warning", "Extreme", "⚠️ Severe hail. Seek shelter immediately.")); if ((weather.wind_gusts_10m ?: 0.0) > 80.0) alerts.add(WeatherAlert("High Wind", "Severe", "⚠️ Damaging wind gusts.")); return alerts }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { RealOSLauncher() } }
    }
}

enum class LauncherTab { HOME, ALL_APPS, CALENDAR, WEATHER }
enum class PermState { INITIAL, ASKING, GRANTED, DENIED }

// Liquid Glass Theme Colors
val GlassBg = Color(0x40FFFFFF) // Transparent white
val GlassBorder = Color(0x66FFFFFF) // Faint white border
val DarkGlassBg = Color(0x801A1A1A) // Darker glass for contrast

@Composable
fun RealOSLauncher() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val wallpaperManager = WallpaperManager.getInstance(context)
    val wallpaperDrawable = remember { wallpaperManager.drawable }
    val wallpaperPainter = remember { wallpaperDrawable?.let { BitmapPainter(it.toBitmap().asImageBitmap()) } }

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

    Box(Modifier.fillMaxSize()) {
        // WALLPAPER
        if (wallpaperPainter != null) {
            Image(painter = wallpaperPainter, contentDescription = "Wallpaper", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) 
        } else { Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(28.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(28.dp))) {
                    NavigationBar(containerColor = Color.Transparent) {
                        NavigationBarItem(selected = currentTab == LauncherTab.CALENDAR, onClick = { currentTab = LauncherTab.CALENDAR; sheetExpanded = false }, icon = { Icon(Icons.Default.DateRange, "Calendar") }, label = { Text("Calendar") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF0078D7), selectedTextColor = Color(0xFF0078D7), unselectedIconColor = Color.White, unselectedTextColor = Color.Gray))
                        NavigationBarItem(selected = currentTab == LauncherTab.HOME || currentTab == LauncherTab.ALL_APPS, onClick = { currentTab = LauncherTab.ALL_APPS; sheetExpanded = false }, icon = { Icon(Icons.Default.Home, "Apps") }, label = { Text("Apps") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF0078D7), selectedTextColor = Color(0xFF0078D7), unselectedIconColor = Color.White, unselectedTextColor = Color.Gray))
                        NavigationBarItem(selected = currentTab == LauncherTab.WEATHER, onClick = { currentTab = LauncherTab.WEATHER; sheetExpanded = false }, icon = { Icon(Icons.Default.Cloud, "Weather") }, label = { Text("Weather") }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF0078D7), selectedTextColor = Color(0xFF0078D7), unselectedIconColor = Color.White, unselectedTextColor = Color.Gray))
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (currentTab) {
                    LauncherTab.HOME -> DesktopView(allApps, favoriteApps) { showSearchOverlay = true }
                    LauncherTab.ALL_APPS -> AllAppsMenu(allApps)
                    LauncherTab.CALENDAR -> CalendarView()
                    LauncherTab.WEATHER -> WeatherView(weatherTemp, weatherCode, weatherLocation, weatherLoading, locationPermState, { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
                }
            }
        }

        // Liquid Glass Swipe-Up Sheet
        BottomSheetOverlay(
            expanded = sheetExpanded,
            onToggle = { sheetExpanded = !sheetExpanded },
            clockTime = currentTime, dateStr = currentDate,
            weatherTemp = weatherTemp, weatherCode = weatherCode,
            weatherDesc = getWeatherDesc(weatherCode ?: 0), weatherEmoji = getWeatherEmoji(weatherCode ?: 0),
            location = weatherLocation, alerts = activeAlerts
        )

        if (showSearchOverlay) SearchOverlay(allApps) { showSearchOverlay = false }
    }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.BottomSheetOverlay(
    expanded: Boolean, onToggle: () -> Unit, clockTime: String, dateStr: String,
    weatherTemp: Double?, weatherCode: Int?, weatherDesc: String, weatherEmoji: String,
    location: String, alerts: List<WeatherAlert>
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val collapsedH = 40.dp
    val expandedH = screenH * 0.75f
    val height by animateDpAsState(if (expanded) expandedH else collapsedH, tween(280), label = "sheetH")
    val bottomPad by animateDpAsState(if (expanded) 0.dp else 90.dp, tween(280), label = "sheetPad")
    var dragAccum by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = bottomPad, start = 16.dp, end = 16.dp) // Floats above dock
            .height(height)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(GlassBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
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
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, "Swipe", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(clockTime, fontSize = 56.sp, fontWeight = FontWeight.Light, color = Color.White)
                Text(dateStr, fontSize = 16.sp, color = Color.White.copy(alpha=0.7f))
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(weatherEmoji, fontSize = 40.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${weatherTemp?.toInt() ?: "--"}°C", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("$weatherDesc • $location", fontSize = 13.sp, color = Color.White.copy(alpha=0.7f))
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("This month", fontSize = 13.sp, color = Color.White.copy(alpha=0.7f), fontWeight = FontWeight.Bold)
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
                val day = idx + 1; val isToday = day == currentDay
                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (isToday) Box(Modifier.size(24.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) { Text("$day", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) } 
                    else Text("$day", fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun DesktopView(allApps: List<AppInfo>, favoriteApps: List<AppInfo>, onSearchClick: () -> Unit) {
    val initialApps = allApps.take(6)
    Box(Modifier.fillMaxSize()) {
        // Top Left Desktop Icons
        Column(modifier = Modifier.padding(16.dp).width(160.dp)) {
            initialApps.chunked(2).forEach { rowApps ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    rowApps.forEach { app ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                            Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(48.dp))
                            Text(app.label, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Right Liquid Glass Dock
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).width(72.dp).clip(RoundedCornerShape(36.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(36.dp)).padding(vertical = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                favoriteApps.forEach { app -> Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))) }
            }
        }

        // Bottom Center Search Widget
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).width(280.dp).clip(RoundedCornerShape(28.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(28.dp)).padding(16.dp)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.3f)).border(1.dp, GlassBorder, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 8.dp).combinedClickable(onClick = onSearchClick), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, "Search", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search apps...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    favoriteApps.take(4).forEach { app -> Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(40.dp)) }
                }
            }
        }
    }
}

@Composable
fun SearchOverlay(allApps: List<AppInfo>, onDismiss: () -> Unit) {
    val context = LocalContext.current; val view = LocalView.current
    var query by remember { mutableStateOf("") }
    val filteredApps = allApps.filter { it.label.contains(query, ignoreCase = true) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f))) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, "Search", tint = Color.White); Spacer(Modifier.width(8.dp))
                BasicTextField(value = query, onValueChange = { query = it }, textStyle = TextStyle(color = Color.White, fontSize = 18.sp), modifier = Modifier.weight(1f), decorationBox = { inner -> if (query.isEmpty()) Text("Search apps...", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp); inner() })
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { val intent = context.packageManager.getLaunchIntentForPackage(app.packageName); if (intent != null) context.startActivity(intent, ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(48.dp)); Spacer(Modifier.width(16.dp)); Text(app.label, color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun AllAppsMenu(allApps: List<AppInfo>) {
    val context = LocalContext.current; val view = LocalView.current
    // Floating glass list instead of solid background
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(24.dp)).padding(16.dp)) {
            Text("All Apps", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
            LazyColumn {
                items(allApps, key = { it.packageName }) { app ->
                    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { val intent = context.packageManager.getLaunchIntentForPackage(app.packageName); if (intent != null) context.startActivity(intent, ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(app.icon.toBitmap(100, 100).asImageBitmap(), app.label, modifier = Modifier.size(48.dp)); Spacer(Modifier.width(16.dp)); Text(app.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarView() {
    val cal = Calendar.getInstance()
    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDay = cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1

    // Floating Glass Calendar Card
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(24.dp)).padding(24.dp)) {
            Text(monthName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { listOf("S","M","T","W","T","F","S").forEach { Text(it, fontWeight = FontWeight.Bold, color = Color(0xFF0078D7)) } }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(7)) {
                items(firstDay) { Spacer(Modifier.size(1.dp)) }
                items(daysInMonth) { idx ->
                    val day = idx + 1; val isToday = day == currentDay
                    Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (isToday) Box(Modifier.size(36.dp).background(Color.Red, CircleShape), contentAlignment = Alignment.Center) { Text("$day", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold) } else { Text("$day", fontSize = 16.sp, color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherView(temp: Double?, code: Int?, location: String, loading: Boolean, permState: PermState, onRetry: () -> Unit) {
    // Floating Glass Weather Card
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GlassBg).border(1.dp, GlassBorder, RoundedCornerShape(24.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
