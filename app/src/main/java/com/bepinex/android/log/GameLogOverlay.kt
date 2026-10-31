package com.bepinex.android.log

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bepinex.android.BepInExPaths
import com.bepinex.android.BoundedTextFile
import com.bepinex.android.R
import java.io.File
import java.util.ArrayDeque

/**
 * Floating FAB + log panel added directly to the game Activity's DecorView.
 * Only the view's own bounds intercept touches — game stays fully playable.
 *
 * Ported from the upstream GameLogOverlay. Text resolves through [LogText] so the
 * overlay keeps the launcher language even after the runtime swaps the game's
 * resources onto the Activity.
 */
object GameLogOverlay {

    private const val MAX_LOG_LINES = 400
    private val handler = Handler(Looper.getMainLooper())
    private var fab: View? = null
    private var panel: View? = null
    private var panelVisible = false
    private var pollJob: Runnable? = null

    fun show(activity: Activity, packageName: String, logFile: File? = null, showPanel: Boolean = false, showFab: Boolean = true) {
        val decorView = activity.window?.decorView as? ViewGroup ?: return
        val resolvedLogFile = logFile ?: BepInExPaths.getLogFile(packageName)
        // Launcher resources in the user-selected language (the activity's own resources
        // belong to the game once the runtime swaps them in).
        val res = LogText.launcherContext(activity)

        // Remove old if any
        remove(decorView)

        val density = activity.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val fabSize = (44 * density).toInt()
        val panelWidth = (360 * density).toInt().coerceAtMost(
            (decorView.width - margin * 2).coerceAtLeast(fabSize)
        )
        val panelHeight = (280 * density).toInt().coerceAtMost(
            (decorView.height - margin * 2).coerceAtLeast(fabSize)
        )
        // --- FAB ---
        val fabIcon = ImageView(activity).apply {
            val icon = BitmapFactory.decodeResource(res.resources, R.drawable.console_icon)
            if (icon != null) setImageBitmap(icon)
            visibility = View.VISIBLE
            alpha = 1f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((5 * density).toInt(), (5 * density).toInt(),
                (5 * density).toInt(), (5 * density).toInt())
            contentDescription = res.getString(R.string.log_overlay_open)
        }
        val fabView = FrameLayout(activity).apply {
            background = null
            addView(fabIcon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))
            elevation = 0f
        }
        val fabParams = FrameLayout.LayoutParams(fabSize, fabSize).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(margin, 0, 0, margin)
        }

        // --- Panel ---
        val panelBg = GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(LogColorizer.panelBackground)
            setStroke((1.2f * density).toInt(), LogColorizer.panelStroke)
        }
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val logTextView = TextView(activity).apply {
            setTextColor(LogColorizer.muted)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.15f)
            setPadding(
                (10 * density).toInt(),
                (8 * density).toInt(),
                (10 * density).toInt(),
                (10 * density).toInt()
            )
            setTextIsSelectable(true)
            text = res.getString(R.string.log_overlay_waiting)
        }
        scrollView.addView(
            logTextView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val accentBar = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2f * density
                setColor(LogColorizer.accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                (3 * density).toInt(),
                (14 * density).toInt()
            ).apply {
                marginStart = (12 * density).toInt()
                marginEnd = (8 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val headerTitle = TextView(activity).apply {
            text = res.getString(R.string.log_title)
            setTextColor(LogColorizer.accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val headerHint = TextView(activity).apply {
            text = res.getString(R.string.log_overlay_drag_hint)
            setTextColor(LogColorizer.muted)
            textSize = 10f
            includeFontPadding = false
        }
        val headerText = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            addView(headerTitle)
            addView(
                headerHint,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * density).toInt() }
            )
        }
        val minimizeBtn = TextView(activity).apply {
            text = "−"
            contentDescription = res.getString(R.string.log_overlay_minimize)
            setTextColor(LogColorizer.text)
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = null
            layoutParams = LinearLayout.LayoutParams(
                (30 * density).toInt(),
                (30 * density).toInt()
            ).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (10 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(LogColorizer.headerBackground)
                cornerRadii = floatArrayOf(
                    16f * density, 16f * density,
                    16f * density, 16f * density,
                    0f, 0f, 0f, 0f
                )
            }
            addView(accentBar)
            addView(
                headerText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(minimizeBtn)
        }
        val divider = View(activity).apply {
            setBackgroundColor(LogColorizer.panelStroke)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            )
        }
        val panelLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            elevation = 16f * density
            clipToOutline = true
            addView(
                header,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(divider)
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        val panelParams = FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setMargins(0, 0, margin, 0)
        }
        panelParams.width = panelWidth

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
        var downPanelX = 0f
        var downPanelY = 0f
        var isPanelDragging = false

        headerText.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downPanelX = panelContainer.left.toFloat()
                    downPanelY = panelContainer.top.toFloat()
                    isPanelDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isPanelDragging && (dx * dx + dy * dy) > 25 * density * density) {
                        isPanelDragging = true
                    }
                    if (isPanelDragging) {
                        val maxLeft = (decorView.width - panelWidth).coerceAtLeast(0)
                        val maxTop = (decorView.height - panelHeight).coerceAtLeast(0)
                        val updatedParams = panelContainer.layoutParams as FrameLayout.LayoutParams
                        updatedParams.gravity = Gravity.TOP or Gravity.START
                        updatedParams.leftMargin = (downPanelX + dx).toInt().coerceIn(0, maxLeft)
                        updatedParams.topMargin = (downPanelY + dy).toInt().coerceIn(0, maxTop)
                        panelContainer.layoutParams = updatedParams
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanelDragging = false
                    true
                }
                else -> false
            }
        }

        fun jumpToBottom() {
            scrollView.post {
                val child = scrollView.getChildAt(0) ?: return@post
                val target = (child.bottom - scrollView.height).coerceAtLeast(0)
                scrollView.scrollTo(0, target)
            }
        }

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
                        // Move via margins
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
                        if (panelVisible) jumpToBottom()
                    }
                    true
                }
                else -> false
            }
        }

        minimizeBtn.setOnClickListener {
            panelVisible = false
            panelContainer.visibility = View.GONE
        }

        // Add to DecorView
        decorView.addView(panelContainer, panelParams)
        if (showFab) {
            decorView.addView(fabView, fabParams)
            this.fab = fabView
        }
        this.panel = panelContainer

        // Show panel immediately if requested (e.g. from modpack log button)
        if (showPanel) {
            panelVisible = true
            panelContainer.visibility = View.VISIBLE
        }

        // Poll log
        var lastSize = 0L
        val logLines = ArrayDeque<String>(MAX_LOG_LINES)
        pollJob = object : Runnable {
            override fun run() {
                try {
                    if (resolvedLogFile.isFile) {
                        val length = resolvedLogFile.length()
                        if (length < lastSize) {
                            logLines.clear()
                            lastSize = 0L
                            logTextView.text = ""
                        }
                        if (length > lastSize) {
                            val start = if (length - lastSize > BoundedTextFile.LIMIT_BYTES) {
                                logLines.clear()
                                length - BoundedTextFile.LIMIT_BYTES
                            } else {
                                lastSize
                            }
                            val text = BoundedTextFile.decodeTail(
                                BoundedTextFile.readRange(resolvedLogFile, start, length),
                                skipPartialLine = BoundedTextFile.startsMidLine(resolvedLogFile, start)
                            )
                            val newLines = text.lines().filter { it.isNotBlank() }
                            if (newLines.isNotEmpty()) {
                                for (line in newLines) {
                                    if (logLines.size >= MAX_LOG_LINES) logLines.removeFirst()
                                    logLines.addLast(line)
                                }
                                logTextView.setText(
                                    LogColorizer.spannable(logLines),
                                    TextView.BufferType.SPANNABLE
                                )
                                jumpToBottom()
                            }
                            lastSize = length
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
