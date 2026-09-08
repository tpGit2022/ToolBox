package com.seeksky.toolbox

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class AutoGestureAccessibilityService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var targetView: TextView? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var controllerView: LinearLayout? = null
    private var statusView: TextView? = null
    private var operationToken = 0
    private var config = GestureConfig(10, 500L, 5, 800L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        cancelCurrentOperation("已中止")
    }

    override fun onDestroy() {
        closeController()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun showControllerInternal(newConfig: GestureConfig) {
        config = newConfig
        if (targetView == null) addTargetView()
        if (controllerView == null) addControllerView()
        statusView?.text = "就绪"
    }

    private fun addTargetView() {
        val size = dp(52)
        val target = TextView(this).apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "拖动以选择点击位置"
            background = circleDrawable(Color.rgb(25, 118, 210), Color.WHITE)
        }
        val (screenWidth, screenHeight) = screenSize()
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = screenWidth / 2 - size / 2
            y = screenHeight / 2 - size / 2
        }
        installDragHandler(target, params)
        windowManager.addView(target, params)
        targetView = target
        targetParams = params
    }

    private fun addControllerView() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedDrawable(
                Color.argb(235, 35, 35, 40),
                Color.DKGRAY,
                dp(14).toFloat()
            )
        }
        val status = TextView(this).apply {
            text = "就绪"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
        panel.addView(status, LinearLayout.LayoutParams(dp(92), WindowManager.LayoutParams.WRAP_CONTENT))
        panel.addView(actionButton("点击") { startClicks() })
        panel.addView(actionButton("上滑") { startSwipes(up = true) })
        panel.addView(actionButton("下滑") { startSwipes(up = false) })
        panel.addView(actionButton("停止") { cancelCurrentOperation("已停止") })
        panel.addView(actionButton("关闭") { closeController() })

        val params = overlayParams(
            dp(108),
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            x = dp(10)
            y = dp(110)
        }
        windowManager.addView(panel, params)
        controllerView = panel
        statusView = status
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(52)).apply {
                topMargin = dp(3)
            }
        }

    private fun startClicks() {
        val params = targetParams ?: return
        val view = targetView ?: return
        cancelCurrentOperation(null)
        val token = operationToken
        val x = params.x + view.width / 2f
        val y = params.y + view.height / 2f
        view.visibility = View.INVISIBLE
        statusView?.text = "点击 0/${config.clickCount}"
        handler.postDelayed({ runClick(token, x, y, 0) }, OVERLAY_UPDATE_DELAY_MS)
    }

    private fun runClick(token: Int, x: Float, y: Float, completed: Int) {
        if (token != operationToken) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val done = completed + 1
                if (token != operationToken) return
                statusView?.text = "点击 $done/${config.clickCount}"
                if (done >= config.clickCount) {
                    finishOperation(token)
                } else {
                    handler.postDelayed(
                        { runClick(token, x, y, done) },
                        config.clickIntervalMs
                    )
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (token == operationToken) cancelCurrentOperation("手势被系统取消")
            }
        }, null)
        if (!accepted && token == operationToken) cancelCurrentOperation("无法执行手势")
    }

    private fun startSwipes(up: Boolean) {
        cancelCurrentOperation(null)
        val token = operationToken
        targetView?.visibility = View.INVISIBLE
        val name = if (up) "上滑" else "下滑"
        statusView?.text = "$name 0/${config.swipeCount}"
        handler.postDelayed({ runSwipe(token, up, 0) }, OVERLAY_UPDATE_DELAY_MS)
    }

    private fun runSwipe(token: Int, up: Boolean, completed: Int) {
        if (token != operationToken) return
        val (width, height) = screenSize()
        val x = width * 0.5f
        val upperY = height * 0.28f
        val lowerY = height * 0.72f
        val path = Path().apply {
            moveTo(x, if (up) lowerY else upperY)
            lineTo(x, if (up) upperY else lowerY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val done = completed + 1
                if (token != operationToken) return
                val name = if (up) "上滑" else "下滑"
                statusView?.text = "$name $done/${config.swipeCount}"
                if (done >= config.swipeCount) {
                    finishOperation(token)
                } else {
                    handler.postDelayed(
                        { runSwipe(token, up, done) },
                        config.swipeIntervalMs
                    )
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (token == operationToken) cancelCurrentOperation("手势被系统取消")
            }
        }, null)
        if (!accepted && token == operationToken) cancelCurrentOperation("无法执行手势")
    }

    private fun finishOperation(token: Int) {
        if (token != operationToken) return
        operationToken++
        targetView?.visibility = View.VISIBLE
        statusView?.text = "已完成"
    }

    private fun cancelCurrentOperation(message: String?) {
        operationToken++
        handler.removeCallbacksAndMessages(null)
        targetView?.visibility = View.VISIBLE
        if (message != null) statusView?.text = message
    }

    private fun closeController() {
        cancelCurrentOperation(null)
        targetView?.let { runCatching { windowManager.removeView(it) } }
        controllerView?.let { runCatching { windowManager.removeView(it) } }
        targetView = null
        targetParams = null
        controllerView = null
        statusView = null
    }

    private fun installDragHandler(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val (screenWidth, screenHeight) = screenSize()
                    params.x = (initialX + event.rawX - initialTouchX).roundToInt()
                        .coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
                    params.y = (initialY + event.rawY - initialTouchY).roundToInt()
                        .coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity }

    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    } else {
        resources.displayMetrics.run { widthPixels to heightPixels }
    }

    private fun circleDrawable(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), stroke)
    }

    private fun roundedDrawable(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAP_DURATION_MS = 40L
        private const val SWIPE_DURATION_MS = 350L
        private const val OVERLAY_UPDATE_DELAY_MS = 80L

        @Volatile
        private var instance: AutoGestureAccessibilityService? = null

        fun showController(config: GestureConfig): Boolean {
            val service = instance ?: return false
            service.handler.post { service.showControllerInternal(config) }
            return true
        }
    }
}
