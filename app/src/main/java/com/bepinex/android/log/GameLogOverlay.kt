package com.bepinex.android.log

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bepinex.android.BepInExPaths
import java.io.File
import java.io.RandomAccessFile

/**
 * Floating FAB + log panel added directly to the game Activity's DecorView.
 * Only the view's own bounds intercept touches — game stays fully playable.
 */
object GameLogOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private var fab: View? = null
    private var panel: View? = null
    private var panelVisible = false
    private var pollJob: Runnable? = null

    fun show(activity: Activity, packageName: String) {
        val decorView = activity.window?.decorView as? ViewGroup ?: return
        val logFile = BepInExPaths.getLogFile(packageName)

        // Remove old if any
        remove(decorView)

        val density = activity.resources.displayMetrics.density
        val fabSize = (40 * density).toInt()
        val panelWidth = (360 * density).toInt()
        val panelHeight = (280 * density).toInt()
        val margin = (12 * density).toInt()

        // --- FAB ---
        val fabBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC1E1E2E"))
        }
        val fabIcon = TextView(activity).apply {
            text = ">_"
            setTextColor(Color.parseColor("#89B4FA"))
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
        }
        val fabView = FrameLayout(activity).apply {
            background = fabBg
            addView(fabIcon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))
            elevation = 10f * density
        }
        val fabParams = FrameLayout.LayoutParams(fabSize, fabSize).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(margin, 0, 0, margin)
        }

        // --- Panel ---
        val panelBg = GradientDrawable().apply {
            cornerRadius = 10f * density
            setColor(Color.parseColor("#DD1E1E2E"))
        }
        val scrollView = ScrollView(activity)
        val logTextView = TextView(activity).apply {
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setPadding((6 * density).toInt(), (4 * density).toInt(),
                (6 * density).toInt(), (4 * density).toInt())
            setTextIsSelectable(true)
        }
        scrollView.addView(logTextView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val headerText = TextView(activity).apply {
            text = " BepInEx Log "
            setTextColor(Color.parseColor("#89B4FA"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (6 * density).toInt(), 0, (4 * density).toInt())
        }
        val closeBtn = TextView(activity).apply {
            text = "\u2715"
            setTextColor(Color.parseColor("#F38BA8"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt())
        }

        val header = FrameLayout(activity).apply {
            addView(headerText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ))
            addView(closeBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ))
        }

        val panelLayout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = panelBg
            elevation = 12f * density
            addView(header, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(scrollView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ))
        }

        val panelParams = FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setMargins(0, 0, margin, 0)
        }

        val panelContainer = FrameLayout(activity).apply {
            addView(panelLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            visibility = View.GONE
        }

        // Drag state for FAB
        var downX = 0f
        var downY = 0f
        var downFabX = 0f
        var downFabY = 0f
        var isDragging = false

        fabView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downFabX = fabParams.leftMargin.toFloat()
                    downFabY = fabParams.bottomMargin.toFloat()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && (dx * dx + dy * dy) > 25 * density * density) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val newLeft = (downFabX + dx).toInt().coerceIn(0,
                            decorView.width - fabSize)
                        val newBottom = (downFabY - dy).toInt().coerceIn(0,
                            decorView.height - fabSize)
                        fabParams.leftMargin = newLeft
                        fabParams.bottomMargin = newBottom
                        fabParams.gravity = Gravity.BOTTOM or Gravity.START
                        fabView.layoutParams = fabParams
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        panelVisible = !panelVisible
                        panelContainer.visibility = if (panelVisible) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }

        closeBtn.setOnClickListener {
            panelVisible = false
            panelContainer.visibility = View.GONE
        }

        // Add to DecorView
        decorView.addView(panelContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        decorView.addView(fabView, fabParams)

        this.fab = fabView
        this.panel = panelContainer

        // Poll log
        var lastSize = 0L
        pollJob = object : Runnable {
            override fun run() {
                try {
                    if (logFile.exists() && logFile.length() > lastSize) {
                        RandomAccessFile(logFile, "r").use { raf ->
                            raf.seek(lastSize)
                            val buf = ByteArray((raf.length() - lastSize).toInt())
                            raf.readFully(buf)
                            val newLines = String(buf, Charsets.UTF_8)
                                .lines()
                                .filter { it.isNotBlank() }
                            if (newLines.isNotEmpty()) {
                                val sb = StringBuilder(logTextView.text)
                                for (line in newLines) {
                                    sb.appendLine(line)
                                }
                                val full = sb.toString()
                                if (full.length > 5000) {
                                    logTextView.text = full.substring(full.length - 5000)
                                } else {
                                    logTextView.text = full
                                }
                            }
                            lastSize = logFile.length()
                        }
                    }
                } catch (_: Exception) { }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(pollJob!!, 500)
    }

    fun remove(decorView: ViewGroup? = null) {
        pollJob?.let { handler.removeCallbacks(it) }
        pollJob = null
        panelVisible = false
        panel?.let { dv ->
            (dv.parent as? ViewGroup)?.removeView(dv)
        }
        fab?.let { dv ->
            (dv.parent as? ViewGroup)?.removeView(dv)
        }
        panel = null
        fab = null
    }
}
