package com.realos.launcher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: android.graphics.drawable.Drawable
)

class MainActivity : AppCompatActivity() {

    private lateinit var tileGrid: RecyclerView
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerPanel: View
    private lateinit var timeText: TextView
    private lateinit var logoButton: ImageView
    private lateinit var dimView: View
    private lateinit var overlayIcon: ImageView

    private val allApps = mutableListOf<AppEntry>()
    private val homeApps = mutableListOf<AppEntry>()
    private var drawerOpen = false
    private var animating = false

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            timeText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isRealOs()) {
            Toast.makeText(this, "real.launcher only runs on real.os", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tileGrid    = findViewById(R.id.tileGrid)
        drawerList  = findViewById(R.id.drawerList)
        drawerPanel = findViewById(R.id.drawerPanel)
        timeText    = findViewById(R.id.timeText)
        logoButton  = findViewById(R.id.logoButton)
        dimView     = findViewById(R.id.dimView)
        overlayIcon = findViewById(R.id.overlayIcon)

        loadApps()

        tileGrid.layoutManager = GridLayoutManager(this, 3)
        tileGrid.adapter = TileAdapter(homeApps) { entry, iconView ->
            launchWithAnimation(entry, iconView)
        }

        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = DrawerAdapter(allApps) { entry, iconView ->
            closeDrawer()
            drawerPanel.postDelayed({ launchWithAnimation(entry, iconView) }, 240)
        }

        drawerPanel.visibility = View.GONE

        logoButton.setOnClickListener {
            if (drawerOpen) closeDrawer() else openDrawer()
        }

        clockHandler.post(clockRunnable)
    }

    private fun isRealOs(): Boolean {
        val display = Build.DISPLAY ?: ""
        val prop = try {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            m.invoke(null, "ro.realos.version") as? String ?: ""
        } catch (_: Throwable) { "" }
        return display.contains("real.os", true) ||
               display.contains("realos", true) ||
               prop.isNotEmpty()
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val seen = HashSet<String>()

        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            if (!seen.add(pkg)) continue
            val e = AppEntry(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm))
            allApps.add(e)
            if (homeApps.size < 12) homeApps.add(e)
        }
        allApps.sortBy { it.label.lowercase() }
    }

    private fun launchWithAnimation(entry: AppEntry, sourceIcon: View) {
        if (animating) return
        animating = true

        val loc = IntArray(2)
        sourceIcon.getLocationOnScreen(loc)
        val startX = loc[0]
        val startY = loc[1]
        val startW = sourceIcon.width.coerceAtLeast(1)
        val startH = sourceIcon.height.coerceAtLeast(1)

        overlayIcon.setImageDrawable(entry.icon)
        overlayIcon.x = startX.toFloat()
        overlayIcon.y = startY.toFloat()
        overlayIcon.layoutParams = overlayIcon.layoutParams.apply {
            width = startW
            height = startH
        }
        overlayIcon.scaleX = 1f
        overlayIcon.scaleY = 1f
        overlayIcon.rotation = 0f
        overlayIcon.alpha = 1f
        overlayIcon.visibility = View.VISIBLE

        dimView.visibility = View.VISIBLE
        dimView.alpha = 0f
        dimView.animate().alpha(0.85f).setDuration(200).start()

        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val targetScale = (screenW * 0.6f) / startW

        overlayIcon.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .rotation(8f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.15f))
            .withEndAction {
                startApp(entry)
                overlayIcon.postDelayed({
                    overlayIcon.visibility = View.GONE
                    dimView.visibility = View.GONE
                    dimView.alpha = 0f
                    overlayIcon.scaleX = 1f
                    overlayIcon.scaleY = 1f
                    overlayIcon.rotation = 0f
                    animating = false
                }, 450)
            }
            .start()
    }

    private fun startApp(entry: AppEntry) {
        val intent = packageManager.getLaunchIntentForPackage(entry.packageName)
        if (intent != null) startActivity(intent)
    }

    private fun openDrawer() {
        drawerOpen = true
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.translationY = drawerPanel.height.toFloat().coerceAtLeast(1f)
        drawerPanel.animate()
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawerPanel.animate()
            .translationY(drawerPanel.height.toFloat())
            .setDuration(220)
            .withEndAction { drawerPanel.visibility = View.GONE }
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (overlayIcon.visibility == View.VISIBLE) {
            overlayIcon.postDelayed({
                overlayIcon.visibility = View.GONE
                dimView.visibility = View.GONE
                overlayIcon.scaleX = 1f
                overlayIcon.scaleY = 1f
                overlayIcon.rotation = 0f
                animating = false
            }, 100)
        } else {
            animating = false
        }
        clockHandler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else super.onBackPressed()
    }

    class TileAdapter(
        private val items: List<AppEntry>,
        private val onClick: (AppEntry, View) -> Unit
    ) : RecyclerView.Adapter<TileAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.tileIcon)
            val label: TextView = v.findViewById(R.id.tileLabel)
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.icon.setImageDrawable(e.icon)
            holder.label.text = e.label
            holder.itemView.setOnClickListener { onClick(e, holder.icon) }
        }
        override fun getItemCount() = items.size
    }

    class DrawerAdapter(
        private val items: List<AppEntry>,
        private val onClick: (AppEntry, View) -> Unit
    ) : RecyclerView.Adapter<DrawerAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.drawerIcon)
            val label: TextView = v.findViewById(R.id.drawerLabel)
        }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_drawer, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.icon.setImageDrawable(e.icon)
            holder.label.text = e.label
            holder.itemView.setOnClickListener { onClick(e, holder.icon) }
        }
        override fun getItemCount() = items.size
    }
}
