package com.hopengzhe.basketballliveyt

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * v0.5.1：對焦圈動態圖示，疊加在相機預覽畫面（cameraPreviewContainer）最上層，
 * 用來顯示目前的對焦／鎖定位置。本身完全不攔截觸控事件（預設 View 不可點擊，
 * onTouchEvent 一律回傳 false），觸控會直接穿透到底下的 OpenGlView，
 * 因此不會跟 [LiveActivity] 掛在 OpenGlView 上的對焦手勢互相搶事件。
 *
 * - 一般點擊對焦（[LiveActivity.handleFocusGesture] lockAfterFocus=false）：
 *   呼叫 [showScanning] 顯示淡入放大→縮小定住的白色圓框，對焦判定完成後由呼叫端呼叫 [hide] 淡出移除。
 * - 長按鎖定對焦（lockAfterFocus=true）：同樣先 [showScanning]，
 *   對焦判定完成且確定鎖定成功後改呼叫 [showLocked]——圓框換成琥珀色並常駐顯示（不淡出），
 *   讓使用者一眼就能分辨「目前是鎖定狀態」；解除鎖定時呼叫端再呼叫 [hide]。
 */
class FocusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var centerX = 0f
    private var centerY = 0f
    private var currentRadius = 0f
    private var currentAlpha = 0
    private var lockedStyle = false
    private var isVisible = false
    private var animator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_PX
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 觸發點觸／長按當下先顯示的「掃描中」動畫：淡入放大→縮小定住，白色圓框。 */
    fun showScanning(x: Float, y: Float) {
        animator?.cancel()
        centerX = x
        centerY = y
        lockedStyle = false
        isVisible = true
        currentAlpha = FULL_ALPHA
        animator = ValueAnimator.ofFloat(START_SCALE, END_SCALE).apply {
            duration = SCAN_ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentRadius = BASE_RADIUS_PX * (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    /** 長按鎖定判定為對焦已收斂完成時呼叫：換成琥珀色圓框＋中心實心點，常駐顯示直到 [hide]。 */
    fun showLocked(x: Float, y: Float) {
        animator?.cancel()
        centerX = x
        centerY = y
        lockedStyle = true
        isVisible = true
        currentAlpha = FULL_ALPHA
        currentRadius = BASE_RADIUS_PX
        invalidate()
    }

    /** 淡出後移除顯示（一般點擊對焦完成、或解除鎖定時呼叫）。 */
    fun hide() {
        if (!isVisible) return
        animator?.cancel()
        animator = ValueAnimator.ofInt(currentAlpha, 0).apply {
            duration = FADE_OUT_DURATION_MS
            addUpdateListener {
                currentAlpha = it.animatedValue as Int
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isVisible = false
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isVisible) return
        ringPaint.color = if (lockedStyle) LOCKED_COLOR else SCANNING_COLOR
        ringPaint.alpha = currentAlpha
        canvas.drawCircle(centerX, centerY, currentRadius, ringPaint)
        if (lockedStyle) {
            dotPaint.color = LOCKED_COLOR
            dotPaint.alpha = currentAlpha
            canvas.drawCircle(centerX, centerY, DOT_RADIUS_PX, dotPaint)
        }
    }

    private companion object {
        const val BASE_RADIUS_PX = 90f
        const val DOT_RADIUS_PX = 8f
        const val STROKE_WIDTH_PX = 5f
        const val START_SCALE = 1.6f
        const val END_SCALE = 1f
        const val FULL_ALPHA = 255
        const val SCAN_ANIM_DURATION_MS = 350L
        const val FADE_OUT_DURATION_MS = 250L
        const val SCANNING_COLOR = Color.WHITE
        val LOCKED_COLOR = Color.parseColor("#FFC107")
    }
}
