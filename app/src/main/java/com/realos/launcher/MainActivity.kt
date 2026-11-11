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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
    val temperature_2m: Double,
    val relative_humidity_2m: Int,
    val apparent_temperature: Double,
    val weather_code: Int,
    val wind_speed_10m: Double,
    val wind_gusts_10m: Double? = null
)

data class WeatherAlert(val event: String, val severity: String, val headline: String)

interface WeatherApi {
    @GET("v1/forecast")
    suspend fun getWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m"
    ): WeatherResponse
}

fun getWeatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
    51, 53, 55 -> "🌦️"; 61, 63, 65 -> "🌧️"; 71, 73, 75 -> "❄️"
    80, 81, 82 -> "🌧️"; 95, 96, 99 -> "⛈️"; else -> "🌡️"
}

fun getWeatherDesc(code: Int): String = when (code) {
    0 -> "Clear Sky"; 1, 2 -> "Partly Cloudy"; 3 -> "Overcast"
    45, 48 -> "Fog"; 51, 53, 55 -> "Drizzle"; 61, 63, 65 -> "Rain"
    71, 73, 75 -> "Snow"; 80, 81, 82 -> "Rain Showers"
    95 -> "Thunderstorm"; 96, 99 -> "Thunderstorm with Hail"
    else -> "Unknown"
}

// Generates a Windows-style Metro tile color based on the app's package name
fun getMetroColor(packageName: String): Color {
    val colors = listOf(
        Color(0xFF0078D7), // Windows Blue
        Color(0xFFD13438), // Red
        Color(0xFF107C10), // Green
        Color(0xFFFFB900), // Yellow
        Color(0xFF881798), // Purple
        Color(0xFF00B7C3), // Teal
        Color(0xFFE3008C), // Magenta
        Color(0xFF767676)  // Gray
    )
    return colors[Math.abs(packageName.hashCode()) % colors.size]
}

fun generateGlobalAlerts(weather: CurrentWeather): List<WeatherAlert> {
    val alerts = mutableListOf<WeatherAlert>()
    when (weather.weather_code) {
        96 -> alerts.add(WeatherAlert("Hail Warning", "Severe", "⚠️ Thunderstorm with hail. Seek shelter immediately."))
        99 -> alerts.add(WeatherAlert("Severe Hail Warning", "Extreme", "⚠️ SEVERE HAIL. Large damaging hail expected. Stay indoors and away from windows."))
    }
    if ((weather.wind_gusts_10m ?: 0.0) > 100.0) alerts.add(WeatherAlert("Hurricane-Force Wind Warning", "Extreme", "⚠️ Life-threatening winds. Seek shelter immediately in a sturdy building."))
    if (weather.weather_code == 95) alerts.add(WeatherAlert("Thunderstorm Warning", "Severe", "⚠️ Strong thunderstorms. Staying indoors is recommended."))
    if (weather.weather_code == 65 || weather.weather_code == 82) alerts.add(WeatherAlert("Flash Flood Warning", "Severe", "⚠️ Heavy rainfall. Risk of flash flooding. Avoid low-lying areas."))
    if (weather.weather_code == 75 || weather.weather_code == 86) alerts.add(WeatherAlert("Blizzard Warning", "Severe", "⚠️ Heavy snow. Travel is highly discouraged. Stay indoors."))
    if ((weather.wind_gusts_10m ?: 0.0) > 80.0) alerts.add(WeatherAlert("High Wind Warning", "Severe", "⚠️ Damaging wind gusts up to ${weather.wind_gusts_10m?.toInt()} km/h. Secure loose objects."))
    if (weather.temperature_2m > 40.0) alerts.add(WeatherAlert("Extreme Heat Warning", "Severe", "⚠️ Dangerously high temperatures. Drink plenty of water."))
    if (weather.temperature_2m < -5.0) alerts.add(WeatherAlert("Extreme Cold Warning", "Severe", "⚠️ Dangerously low temperatures. Risk of hypothermia. Limit time outdoors."))
    return alerts
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { RealOSHomeScreen() } }
    }
}

@Composable
fun RealOSHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

    var isEditMode by remember { mutableStateOf(false) }
    var columns by remember { mutableStateOf(3) } // Default Windows 8 size
    var is24HourFormat by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf("") }

    var weatherTemp by remember { mutableStateOf<Double?>(null) }
    var weatherHumidity by remember { mutableStateOf<Int?>(null) }
    var weatherCode by remember { mutableStateOf<Int?>(null) }
    var weatherWind by remember { mutableStateOf<Double?>(null) }
    var weatherLocation by remember { mutableStateOf("Locating...") }
    var weatherLoading by remember { mutableStateOf(true) }
    var weatherError by remember { mutableStateOf<String?>(null) }
    var activeAlerts by remember { mutableStateOf<List<WeatherAlert>>(emptyList()) }
    var severeDisasterAlert by remember { mutableStateOf<WeatherAlert?>(null) }
    var dismissedAlertEvent by remember { mutableStateOf<String?>(null) }

    val installedApps = remember { getInstalledApps(context) }
    val appList = remember { mutableStateListOf<AppInfo>().apply { addAll(installedApps) } }

    LaunchedEffect(is24HourFormat) {
        while (true) {
            val format = if (is24HourFormat) "HH:mm" else "hh:mm a"
            currentTime = SimpleDateFormat(format, Locale.getDefault()).format(Date())
            delay(60000L)
        }
    }
    val currentDate = remember { SimpleDateFormat("d. MMMM yyyy", Locale.getDefault()).format(Date()) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            scope.launch {
                fetchGlobalData(context) { t, h, c, w, loc, alerts, err ->
                    weatherTemp = t; weatherHumidity = h; weatherCode = c; weatherWind = w
                    weatherLocation = loc ?: "Unknown"; activeAlerts = alerts; weatherError = err; weatherLoading = false
                }
            }
        } else {
            weatherLocation = "Location Denied"; weatherError = "Location denied"; weatherLoading = false
        }
    }

    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        
        while (true) {
            fetchGlobalData(context) { t, h, c, w, loc, alerts, err ->
                weatherTemp = t; weatherHumidity = h; weatherCode = c; weatherWind = w
                weatherLocation = loc ?: "Unknown"; activeAlerts = alerts; weatherError = err; weatherLoading = false
                val extreme = alerts.firstOrNull { it.severity == "Extreme" && it.event != dismissedAlertEvent }
                if (extreme != null) {
                    severeDisasterAlert = extreme
                    val intent = Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
                    context.startActivity(intent)
                }
            }
            delay(120000L)
        }
    }

    severeDisasterAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = Color(0xFFB71C1C),
            title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(40.dp)); Spacer(Modifier.width(12.dp)); Text("CRITICAL WARNING", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) } },
            text = { Column { Text(alert.event, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(alert.headline, color = Color.White, fontSize = 16.sp) } },
            confirmButton = { TextButton(onClick = { dismissedAlertEvent = alert.event; severeDisasterAlert = null }) { Text("I Understand", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
        )
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 10.dp,
        sheetContainerColor = Color(0xFF1F1F1F),
        sheetContent = {
            Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                activeAlerts.forEach { alert -> AlertBanner(alert = alert, modifier = Modifier.padding(bottom = 8.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = currentTime, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
                    IconButton(onClick = { is24HourFormat = !is24HourFormat }) { Icon(Icons.Default.Settings, "Settings", tint = Color.Gray) }
                }
                Text(text = currentDate, color = Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(24.dp))
                if (!weatherLoading && weatherError == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(getWeatherEmoji(weatherCode ?: 0), fontSize = 40.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("${weatherTemp?.toInt() ?: "--"}°C", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            Text("${getWeatherDesc(weatherCode ?: 0)} • $weatherLocation", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Scaffold(
            modifier = Modifier.padding(padding),
            bottomBar = {
                Surface(color = Color(0xFF1F1F1F), shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(0) } }) {
                            Icon(Icons.Default.DateRange, "Calendar", tint = if(pagerState.currentPage == 0) Color(0xFF0078D7) else Color.Gray)
                        }
                        Box(
                            modifier = Modifier.size(60.dp).clip(CircleShape).background(if (isEditMode) Color(0xFF0078D7).copy(alpha = 0.2f) else Color.Transparent)
                                .combinedClickable(onClick = { scope.launch { pagerState.animateScrollToPage(1) } }, onLongClick = { isEditMode = !isEditMode }),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(painterResource(id = R.drawable.real_os_logo), "Logo", modifier = Modifier.size(48.dp))
                        }
                        IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(2) } }) {
                            Icon(Icons.Default.Star, "Weather", tint = if(pagerState.currentPage == 2) Color(0xFF0078D7) else Color.Gray)
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { isEditMode = true }) }
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (isEditMode) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Edit Mode: Drag Apps", color = Color.Gray, fontSize = 14.sp)
                            Button(onClick = { if (columns < 5) columns++ else columns = 2 }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D7))) { Text("Grid: $columns cols") }
                        }
                    }
                    if (activeAlerts.isNotEmpty()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            activeAlerts.forEach { alert -> AlertBanner(alert = alert, modifier = Modifier.padding(bottom = 8.dp)) }
                        }
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        when (page) {
                            0 -> CalendarTabContent()
                            1 -> HomeTabContent(appList, isEditMode, columns)
                            2 -> WeatherTabContent(weatherTemp, weatherHumidity, weatherCode, weatherWind, weatherLocation, activeAlerts, weatherLoading, weatherError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlertBanner(alert: WeatherAlert, modifier: Modifier = Modifier) {
    val bgColor = when(alert.severity) { "Extreme" -> Color(0xFFB71C1C); "Severe" -> Color(0xFFE65100); else -> Color(0xFFF9A825) }
    Row(modifier = modifier.fillMaxWidth().background(bgColor, RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, "Warning", tint = Color.White, modifier = Modifier.size(32.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = alert.event, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text = alert.headline, color = Color.White, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

suspend fun fetchGlobalData(context: Context, onResult: (Double?, Int?, Int?, Double?, String?, List<WeatherAlert>, String?) -> Unit) {
    try {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val location = withContext(Dispatchers.IO) { fusedLocationClient.lastLocation.await() }
        val lat = location?.latitude ?: 52.52
        val lon = location?.longitude ?: 13.40

        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = withContext(Dispatchers.IO) { @Suppress("DEPRECATION") geocoder.getFromLocation(lat, lon, 1) }
        val address = addresses?.firstOrNull()
        val city = address?.locality ?: address?.subAdminArea ?: address?.adminArea ?: "Unknown"
        val country = address?.countryName ?: ""
        val locationName = if (country.isNotEmpty() && country != city) "$city, $country" else city

        val retrofit = Retrofit.Builder().baseUrl("https://api.open-meteo.com/").addConverterFactory(GsonConverterFactory.create()).build()
        val api = retrofit.create(WeatherApi::class.java)
        val weatherResponse = withContext(Dispatchers.IO) { api.getWeather(lat, lon) }
        val alerts = generateGlobalAlerts(weatherResponse.current)

        onResult(weatherResponse.current.temperature_2m, weatherResponse.current.relative_humidity_2m, weatherResponse.current.weather_code, weatherResponse.current.wind_speed_10m, locationName, alerts, null)
    } catch (e: Exception) {
        onResult(null, null, null, null, "Unknown", emptyList(), e.message ?: "Weather error")
    }
}

@Composable
fun HomeTabContent(appList: MutableList<AppInfo>, isEditMode: Boolean, columns: Int) {
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(appList, key = { _, app -> app.packageName }) { index, app ->
            val view = LocalView.current
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }
            var isDragging by remember { mutableStateOf(false) }
            
            // Windows 8 press animation
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(if (isPressed) 0.90f else 1f, label = "tileScale")

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Makes it a perfect square tile
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset(x = offsetX.dp, y = offsetY.dp)
                    .shadow(if (isDragging) 12.dp else 0.dp, RoundedCornerShape(4.dp))
                    .background(if (isDragging) Color(0xFF333333) else getMetroColor(app.packageName), RoundedCornerShape(4.dp))
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
                                    val rowShift = (offsetY / 100).toInt() * columns
                                    val newIndex = index + colShift + rowShift
                                    if (newIndex in appList.indices) {
                                        val item = appList.removeAt(index)
                                        appList.add(newIndex, item)
                                    }
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            )
                        } else {
                            detectTapGestures(onTap = {
                                val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                if (intent != null) {
                                    val options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height).toBundle()
                                    context.startActivity(intent, options)
                                }
                            })
                        }
                    }
                    .padding(8.dp)
            ) {
                // Windows 8 App Icon (Centered)
                Image(
                    bitmap = app.icon.toBitmap(100, 100).asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.size(48.dp).align(Alignment.Center)
                )
                
                // Windows 8 App Name (Bottom Left)
                Text(
                    text = app.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
    }
}

@Composable
fun CalendarTabContent() {
    val cal = Calendar.getInstance()
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDay = cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1
    
    Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Text(monthName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, fontWeight = FontWeight.Bold, color = Color(0xFF0078D7)) }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(columns = GridCells.Fixed(7)) {
            items(firstDay) { Spacer(Modifier.height(1.dp)) }
            items(daysInMonth) { day ->
                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) { Text("${day + 1}", fontSize = 16.sp, color = Color.White) }
            }
        }
    }
}

@Composable
fun WeatherTabContent(temp: Double?, humidity: Int?, code: Int?, wind: Double?, locationName: String, activeAlerts: List<WeatherAlert>, loading: Boolean, error: String?) {
    Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) {
            Spacer(Modifier.height(100.dp))
            CircularProgressIndicator(color = Color(0xFF0078D7))
            Spacer(Modifier.height(16.dp))
            Text("Fetching global weather...", color = Color.Gray)
        } else if (error != null) {
            Spacer(Modifier.height(100.dp))
            Text("⚠️ $error", color = Color.Red)
        } else {
            Spacer(Modifier.height(24.dp))
            if (activeAlerts.isNotEmpty()) {
                Text("Active Warnings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C), modifier = Modifier.padding(bottom = 8.dp))
                activeAlerts.forEach { alert -> AlertBanner(alert = alert, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                Spacer(Modifier.height(16.dp))
            }
            Text(locationName, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(getWeatherEmoji(code ?: 0), fontSize = 80.sp)
            Text("${temp?.toInt() ?: "--"}°C", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(getWeatherDesc(code ?: 0), fontSize = 24.sp, color = Color.Gray)
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💧 Humidity", fontSize = 14.sp, color = Color.Gray)
                    Text("${humidity ?: "--"}%", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💨 Wind", fontSize = 14.sp, color = Color.Gray)
                    Text("${wind?.toInt() ?: "--"} km/h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
