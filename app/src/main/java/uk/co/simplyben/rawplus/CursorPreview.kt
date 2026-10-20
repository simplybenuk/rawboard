package uk.co.simplyben.rawplus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/** A display-only text view with an insertion marker. It never becomes a text editor. */
class CursorPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {
    var caretOffset: Int? = null
        set(value) {
            if (field == value) return
            field = value
            positionChanged()
        }

    var caretInteractive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isClickable = value
            isFocusable = false
            if (!value) tapEligible = false
            positionChanged()
        }

    var onCaretTap: (Int) -> Unit = {}

    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent)
        strokeWidth = (2f * resources.displayMetrics.density).coerceAtLeast(1f)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tapEligible = false
    private var tappedOffset: Int? = null
    private var caretOn = true
    private var readyForBlink = false
    private val blink = object : Runnable {
        override fun run() {
            if (!canBlink()) return
            caretOn = !caretOn
            invalidate()
            postDelayed(this, BLINK_MILLIS)
        }
    }

    init {
        setTextIsSelectable(false)
        isLongClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        readyForBlink = true
    }

    /** Also available when a caller replaces text while keeping the same insertion offset. */
    fun positionChanged() {
        if (!readyForBlink) return
        removeCallbacks(blink)
        caretOn = true
        invalidate()
        if (canBlink()) postDelayed(blink, BLINK_MILLIS)
    }

    private fun canBlink() = readyForBlink && isAttachedToWindow && isShown &&
        windowVisibility == View.VISIBLE && isEnabled && caretOffset != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!caretOn || !canBlink()) return
        val textLayout = layout ?: return
        val offset = boundary(caretOffset ?: return).coerceAtMost(textLayout.text.length)
        val line = textLayout.getLineForOffset(offset)
        // TextView draws in its scrolled content coordinate system. Total padding
        // includes compound drawables, extended padding, and vertical gravity offset.
        val x = totalPaddingLeft + textLayout.getPrimaryHorizontal(offset)
        val top = totalPaddingTop + textLayout.getLineTop(line).toFloat()
        val bottom = totalPaddingTop + textLayout.getLineBottom(line).toFloat()
        val saved = canvas.save()
        canvas.clipRect(
            (scrollX + compoundPaddingLeft).toFloat(), (scrollY + extendedPaddingTop).toFloat(),
            (scrollX + width - compoundPaddingRight).toFloat(),
            (scrollY + height - extendedPaddingBottom).toFloat()
        )
        canvas.drawLine(x, top, x, bottom, caretPaint)
        canvas.restoreToCount(saved)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!caretInteractive || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tapEligible = true
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > touchSlop ||
                    kotlin.math.abs(event.y - downY) > touchSlop) {
                    tapEligible = false
                    isPressed = false
                }
                // Never disallow parent interception. The surrounding preview owns dragging.
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tapEligible = false
                isPressed = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                val actualTap = tapEligible &&
                    kotlin.math.abs(event.x - downX) <= touchSlop &&
                    kotlin.math.abs(event.y - downY) <= touchSlop &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                tapEligible = false
                isPressed = false
                if (actualTap && layout != null) {
                    val offset = getOffsetForPosition(event.x, event.y)
                    if (offset >= 0) {
                        tappedOffset = boundary(offset)
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tapEligible = false
                tappedOffset = null
                isPressed = false
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        val fromTouch = tappedOffset
        tappedOffset = null
        if (!caretInteractive || !isEnabled) return false
        super.performClick()
        onCaretTap(boundary(fromTouch ?: caretOffset ?: text.length))
        positionChanged()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isEditable = false
        if (caretInteractive && isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
            caretOffset?.let { info.setTextSelection(boundary(it), boundary(it)) }
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
            if (!caretInteractive || !isEnabled || arguments == null) return false
            val startKey = AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT
            val endKey = AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT
            if (!arguments.containsKey(startKey) || !arguments.containsKey(endKey)) return false
            val start = arguments.getInt(startKey)
            val end = arguments.getInt(endKey)
            // This is one insertion point, never a replaceable selection range.
            if (start != end || start !in 0..text.length) return false
            onCaretTap(boundary(start))
            positionChanged()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun boundary(offset: Int): Int {
        var result = offset.coerceIn(0, text.length)
        if (result > 0 && result < text.length &&
            text[result - 1].isHighSurrogate() && text[result].isLowSurrogate()) result--
        return result
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        positionChanged()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        tapEligible = false
        tappedOffset = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        positionChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        positionChanged()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) tapEligible = false
        positionChanged()
    }

    companion object { private const val BLINK_MILLIS = 500L }
}
