package com.fakegps.app.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * 全方向摇杆 View
 * 拖拽时通过回调报告方向角度和是否活跃
 * 角度：0°=北，90°=东，180°=南，270°=西
 */
class JoystickView(context: Context) : View(context) {

    /** 摇杆状态回调 */
    var onJoystickMove: ((angleDegrees: Double, isActive: Boolean) -> Unit)? = null

    // 绘画工具
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 60, 160, 255)
        style = Paint.Style.FILL
    }
    private val paintKnobBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintDirection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 100, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintDirectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 60, 160, 255)
        style = Paint.Style.FILL
    }

    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var knobRadius = 0f
    private var maxDragRadius = 0f  // 摇杆可拖动的最大范围

    // 当前摇杆位置（相对中心偏移）
    private var knobX = 0f
    private var knobY = 0f

    // 是否正在拖拽
    private var isDragging = false

    // 当前角度（度）
    private var currentAngle = 0.0

    init {
        // 启用点击模式
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        centerX = w / 2f
        centerY = h / 2f
        outerRadius = minOf(w, h) / 2f - 8f
        maxDragRadius = outerRadius * 0.55f
        knobRadius = outerRadius * 0.22f
        knobX = centerX
        knobY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 外圈背景（半透明圆）
        canvas.drawCircle(centerX, centerY, outerRadius, paintBg)
        canvas.drawCircle(centerX, centerY, outerRadius, paintBorder)

        // 2. 方向指示十字线
        canvas.drawLine(centerX - outerRadius * 0.6f, centerY,
            centerX + outerRadius * 0.6f, centerY, paintArrow)
        canvas.drawLine(centerX, centerY - outerRadius * 0.6f,
            centerX, centerY + outerRadius * 0.6f, paintArrow)

        // 3. 轻量刻度（8个方向提示小点）
        val tickRadius = outerRadius * 0.75f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val tx = centerX + (tickRadius * Math.cos(angle)).toFloat()
            val ty = centerY + (tickRadius * Math.sin(angle)).toFloat()
            canvas.drawCircle(tx, ty, 3f, paintArrow)
        }

        // 4. 方向指示线（拖拽时显示方向）
        if (isDragging) {
            val dx = knobX - centerX
            val dy = knobY - centerY
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist > knobRadius * 0.5f) {
                // 从中心到摇杆边缘画方向扇形
                val endX = centerX + dx / dist * outerRadius * 0.85f
                val endY = centerY + dy / dist * outerRadius * 0.85f

                // 方向线
                canvas.drawLine(centerX, centerY, endX, endY, paintDirection)

                // 小箭头
                val arrowSize = 12f
                val arrowAngle = Math.toRadians(150.0)
                val dirAngle = Math.atan2(dy.toDouble(), dx.toDouble())
                val ax1 = endX + (arrowSize * Math.cos(dirAngle + arrowAngle)).toFloat()
                val ay1 = endY + (arrowSize * Math.sin(dirAngle + arrowAngle)).toFloat()
                val ax2 = endX + (arrowSize * Math.cos(dirAngle - arrowAngle)).toFloat()
                val ay2 = endY + (arrowSize * Math.sin(dirAngle - arrowAngle)).toFloat()
                val arrowPath = Path().apply {
                    moveTo(endX, endY)
                    lineTo(ax1, ay1)
                    lineTo(ax2, ay2)
                    close()
                }
                canvas.drawPath(arrowPath, paintDirectionFill)
                canvas.drawPath(arrowPath, paintDirection)
            }
        }

        // 5. 摇杆 knob（拖拽的球）
        canvas.drawCircle(knobX, knobY, knobRadius, paintKnob)
        canvas.drawCircle(knobX, knobY, knobRadius, paintKnobBorder)

        // 6. 中心小圆点
        canvas.drawCircle(centerX, centerY, 4f, paintArrow)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val dx = x - centerX
        val dy = y - centerY
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 检查触摸是否在外圈内
                if (dist <= outerRadius) {
                    isDragging = true
                    updateKnobPosition(x, y, dx, dy, dist)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    updateKnobPosition(x, y, dx, dy, dist)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    // 回弹到中心
                    knobX = centerX
                    knobY = centerY
                    onJoystickMove?.invoke(currentAngle, false)
                    currentAngle = 0.0
                    invalidate()
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnobPosition(x: Float, y: Float, dx: Float, dy: Float, dist: Float) {
        if (dist > maxDragRadius && dist > 0) {
            // 限制在最大拖动半径内
            knobX = centerX + dx / dist * maxDragRadius
            knobY = centerY + dy / dist * maxDragRadius
        } else {
            knobX = x
            knobY = y
        }

        // 计算方向角度（0°=北，顺时针增加）
        val dxFromCenter = knobX - centerX
        val dyFromCenter = knobY - centerY
        val actualDist = Math.sqrt(
            (dxFromCenter * dxFromCenter + dyFromCenter * dyFromCenter).toDouble()
        ).toFloat()

        if (actualDist > knobRadius * 0.3f) {
            // atan2(dy, dx) 返回弧度，0=右，逆时针为正
            // 转为 0°=北，顺时针
            val rad = Math.atan2(dyFromCenter.toDouble(), dxFromCenter.toDouble())
            currentAngle = (Math.toDegrees(rad) - 90.0 + 360.0) % 360.0
            onJoystickMove?.invoke(currentAngle, true)
        } else {
            // 太靠近中心，停止移动
            if (isDragging) {
                onJoystickMove?.invoke(currentAngle, false)
                currentAngle = 0.0
            }
        }

        invalidate()
    }

    /** 重置摇杆到中心位置 */
    fun reset() {
        isDragging = false
        knobX = centerX
        knobY = centerY
        currentAngle = 0.0
        onJoystickMove?.invoke(0.0, false)
        invalidate()
    }
}
