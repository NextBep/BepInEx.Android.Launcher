﻿﻿package com.bepinex.android.log

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Android foreground service that shows a floating log overlay on top of the game.
 *
 * The overlay is a small circular button by default. Tapping it expands a log viewer
 * showing real-time BepInEx log output from [BepInExLogReader].
 *
 * Usage:
 *   LogOverlayService.start(context, packageName)   // when game starts
 *   LogOverlayService.stop(context)                  // when game process ends
 */
class LogOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var scope: CoroutineScope? = null
    private var currentPackageName: String? = null

    companion object {
        private const val TAG = "LogOverlayService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "bepinex_log_overlay"

        fun start(context: Context, packageName: String) {
            val intent = Intent(context, LogOverlayService::class.java).apply {
                putExtra("packageName", packageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LogOverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentPackageName = intent?.getStringExtra("packageName") ?: currentPackageName
        if (overlayView == null) {
            createOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        scope?.cancel()
        super.onDestroy()
    }

    // Notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BepInEx Log Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when BepInEx floating log is active in-game"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(currentPackageName ?: packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BepInEx Log")
                .setContentText("Floating log active  -- tap to return to game")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("BepInEx Log")
                .setContentText("Floating log active")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // Overlay

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val composeView = ComposeView(this).apply {
            setContent { OverlayContent { removeOverlay() } }
        }

        // Setup lifecycle for ComposeView
        val lifecycleOwner = FakeLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(FakeSavedStateRegistryOwner())

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayView = composeView
        windowManager?.addView(overlayView, params)

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        overlayView = null
    }

    // Overlay Compose UI

    @Composable
    private fun OverlayContent(onClose: () -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        val logLines by BepInExLogReader.lines.collectAsState()

        // Start log watching
        val pkg = currentPackageName
        LaunchedEffect(pkg) {
            if (pkg != null) {
                scope = CoroutineScope(Dispatchers.IO)
                BepInExLogReader.startWatching(pkg, scope!!)
            }
        }

        if (expanded) {
            // Expanded log viewer
            Card(
                modifier = Modifier
                    .width(320.dp)
                    .height(420.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E2E)
                ),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "BepInEx Log",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClose) {
                            Text("Close", color = Color(0xFFF38BA8), fontSize = 12.sp)
                        }
                        TextButton(onClick = { expanded = false }) {
                            Text(" -- , color = Color(0xFF89B4FA), fontSize = 16.sp)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // Log lines
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    ) {
                        items(logLines.takeLast(100).size) { idx ->
                            val line = logLines.takeLast(100)[idx]
                            val lineColor = when (line.level) {
                                BepInExLogReader.LogLevel.FATAL,
                                BepInExLogReader.LogLevel.ERROR -> Color(0xFFF38BA8)
                                BepInExLogReader.LogLevel.WARNING -> Color(0xFFFAB387)
                                BepInExLogReader.LogLevel.DEBUG -> Color(0xFF6C7086)
                                else -> Color(0xFFCDD6F4)
                            }
                            Text(
                                "[${line.level.label[0]}] ${line.source.ifEmpty { "" }} ${line.message}",
                                color = lineColor,
                                fontSize = 10.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        } else {
            // Minimized floating button
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E2E).copy(alpha = 0.75f))
                    .clickable { expanded = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = "Log",
                    tint = Color(0xFF89B4FA),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Minimal LifecycleOwner for ComposeView in a WindowManager overlay.
 */
private class FakeLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        registry.handleLifecycleEvent(event)
    }
}

/**
 * Minimal SavedStateRegistryOwner for ComposeView.
 */
private class FakeSavedStateRegistryOwner : SavedStateRegistryOwner {
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val lifecycle: Lifecycle get() = throw UnsupportedOperationException()
}
