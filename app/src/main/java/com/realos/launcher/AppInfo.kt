package com.realos.launcher

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
    
    return resolveInfos
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            val icon = try { it.loadIcon(pm) } catch (e: Exception) { pm.getApplicationIcon(it.activityInfo.packageName) }
            AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, icon)
        }.sortedBy { it.label }
}
