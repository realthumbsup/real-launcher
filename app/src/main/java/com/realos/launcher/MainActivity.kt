package com.realos.launcher

package 

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val pagerState = rememberPagerState(pageCount = { 2 })
    
    val bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

    var isEditMode by remember { mutableStateOf(false) }
    var columns by remember { mutableStateOf(4) }
    val installedApps = remember { getInstalledApps(context) }
    val appList = remember { mutableStateListOf<AppInfo>().apply { addAll(installedApps) } }

    val currentDate = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()) }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 80.dp,
        sheetContainerColor = Color(0xFF1E1E1E),
        sheetContent = {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = currentDate, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "No new notifications", color = Color.Gray, fontSize = 16.sp)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.White)
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.White,
                contentColor = Color(0xFF42A5F5),
            ) {
                Tab(selected = pagerState.currentPage == 0, onClick = { scope.launch { pagerState.animateScrollToPage(0) } }, text = { Text("Home") })
                Tab(selected = pagerState.currentPage == 1, onClick = { scope.launch { pagerState.animateScrollToPage(1) } }, text = { Text("Calendar") })
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                if (page == 0) {
                    HomeTabContent(
                        appList = appList,
                        isEditMode = isEditMode,
                        columns = columns,
                        onLogoClick = { isEditMode = !isEditMode },
                        onChangeSize = { if (columns < 6) columns++ else columns = 3 }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Calendar Tab", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeTabContent(
    appList: MutableList<AppInfo>,
    isEditMode: Boolean,
    columns: Int,
    onLogoClick: () -> Unit,
    onChangeSize: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.real_os_logo),
                contentDescription = "Real OS Logo",
                modifier = Modifier
                    .size(80.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onLogoClick() })
                    }
            )
        }

        if (isEditMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edit Mode: Drag to move", color = Color.Gray, fontSize = 12.sp)
                Button(onClick = onChangeSize) {
                    Text("Resize ($columns cols)")
                }
            }
        }

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
                    onLaunch = {
                        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                        if (intent != null) context.startActivity(intent)
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
}

@Composable
fun AppIconItem(
    app: AppInfo,
    isEditMode: Boolean,
    onLaunch: () -> Unit,
    onMove: (Int, Int) -> Unit,
    index: Int
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = offsetX.dp, y = offsetY.dp)
            .pointerInput(isEditMode) {
                if (isEditMode) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x / density
                            offsetY += dragAmount.y / density
                        },
                        onDragEnd = {
                            val colShift = (offsetX / 80).toInt()
                            val rowShift = (offsetY / 100).toInt() * 4
                            val newIndex = index + colShift + rowShift
                            onMove(index, newIndex)
                            offsetX = 0f
                            offsetY = 0f
                        }
                    )
                } else {
                    detectTapGestures(onTap = { onLaunch() })
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
