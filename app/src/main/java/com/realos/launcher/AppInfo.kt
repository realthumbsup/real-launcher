package com.realos.launcher

package 

import android.content.Context
import android.content.Intent

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable
)

fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val resolveInfos = pm.queryIntentActivities(intent, 0)
    
    return resolveInfos.map {
        AppInfo(
            label = it.loadLabel(pm).toString(),
            packageName = it.activityInfo.packageName,
            icon = it.loadIcon(pm)
        )
    }.sortedBy { it.label }
}
