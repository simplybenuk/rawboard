package uk.co.simplyben.rawplus

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView

/** A transcript preview which follows new text until the reader scrolls upward. */
class FollowingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {
    var onFollowingChanged: (Boolean) -> Unit = {}

    var following: Boolean = true
        private set
    /** Follow the newest dictated words at this offset, including a middle-of-draft insertion. */
    var followTargetOffset: Int? = null

    private var touching = false
    private var flinging = false
    private var nonTouchScrolling = false
    private var changingLayout = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tapEligible = false
    private var clicks = 0L
    private var textGeneration = 0L
    private var touchGeneration = 0L
    private var scrollGeneration = 0L
    private var pendingDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var pendingObserver: ViewTreeObserver? = null

    // ScrollView does not expose its scroller's finished state. A short quiet period
    // after its last movement ends our user-fling tracking, without changing the fling.
    private val flingSettled = Runnable {
        flinging = false
        nonTouchScrolling = false
        if (!touching && following) scheduleScroll(toLatest = true)
    }

    fun updateText(view: TextView, text: String, followChanges: Boolean) {
        if (TextUtils.equals(view.text, text)) return
        textGeneration++
        cancelDeferredScroll()
        view.text = text
        if (followChanges && following && !touching && !flinging && !nonTouchScrolling) {
            scheduleScroll(toLatest = true)
        }
    }

    fun followLatest() {
        cancelDeferredScroll()
        stopTrackedFling()
        updateFollowing(true)
        if (!touching) scheduleScroll(toLatest = true)
    }

    fun reviewFromStart() {
        cancelDeferredScroll()
        stopTrackedFling()
        updateFollowing(false)
        // A cursor tap can request source review during child ACTION_UP. The posted
        // scroll checks touch state after dispatch completes, without moving mid-drag.
        scheduleScroll(toLatest = false)
    }

    fun pauseFollowing() {
        cancelDeferredScroll()
        stopTrackedFling()
        updateFollowing(false)
    }

    // Observe child gestures too. The cursor preview consumes taps, while ScrollView
    // may intercept only a later MOVE when the same gesture becomes a drag.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                touchGeneration++
                cancelDeferredScroll()
                removeCallbacks(flingSettled)
                flinging = false
                nonTouchScrolling = false
                downX = event.x
                downY = event.y
                tapEligible = true
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x - downX) > touchSlop ||
                kotlin.math.abs(event.y - downY) > touchSlop) tapEligible = false
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapEligible = false
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (!flinging && following) scheduleScroll(toLatest = true)
        }
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wasTap = event.actionMasked == MotionEvent.ACTION_UP && tapEligible &&
            kotlin.math.abs(event.x - downX) <= touchSlop && kotlin.math.abs(event.y - downY) <= touchSlop &&
            event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
        val previousClicks = clicks
        val handled = super.onTouchEvent(event)
        if (handled && wasTap) {
            val expectedTouch = touchGeneration
            // The superclass may itself post performClick. Let that run first.
            post { if (expectedTouch == touchGeneration && clicks == previousClicks) performClick() }
        }
        return handled
    }

    override fun performClick(): Boolean {
        clicks++
        return super.performClick()
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val upward = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id -> true
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id -> false
            else -> return super.performAccessibilityAction(action, arguments)
        }
        beginNonTouchScroll(upward)
        return super.performAccessibilityAction(action, arguments).also { finishNonTouchDispatch(upward) }
    }

    override fun executeKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.executeKeyEvent(event)
        val upward = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MOVE_HOME -> true
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_END -> false
            KeyEvent.KEYCODE_SPACE -> event.isShiftPressed
            else -> return super.executeKeyEvent(event)
        }
        beginNonTouchScroll(upward)
        return super.executeKeyEvent(event).also { finishNonTouchDispatch(upward) }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
        val amount = if (event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER))
            event.getAxisValue(MotionEvent.AXIS_SCROLL) else event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (amount == 0f) return super.onGenericMotionEvent(event)
        val upward = amount > 0f
        beginNonTouchScroll(upward)
        return super.onGenericMotionEvent(event).also { finishNonTouchDispatch(upward) }
    }

    private fun beginNonTouchScroll(upward: Boolean) {
        touchGeneration++
        cancelDeferredScroll()
        stopTrackedFling()
        nonTouchScrolling = true
        if (upward) updateFollowing(false)
    }

    private fun finishNonTouchDispatch(upward: Boolean) {
        if (!upward && atBottom()) updateFollowing(true)
        // ScrollView can initiate a space-key page scroll while returning false.
        removeCallbacks(flingSettled)
        postDelayed(flingSettled, FLING_QUIET_MS)
    }

    override fun fling(velocityY: Int) {
        flinging = velocityY != 0
        removeCallbacks(flingSettled)
        super.fling(velocityY)
        if (flinging) postDelayed(flingSettled, FLING_QUIET_MS)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (touching && top != oldTop) tapEligible = false
        if (!changingLayout && (touching || flinging || nonTouchScrolling)) {
            if (top < oldTop) updateFollowing(false)
            if (atBottom()) updateFollowing(true)
        }
        if (flinging || nonTouchScrolling) {
            removeCallbacks(flingSettled)
            postDelayed(flingSettled, FLING_QUIET_MS)
        }
    }

    private fun atBottom(): Boolean {
        val child = getChildAt(0) ?: return true
        return scrollY >= (child.bottom - (height - paddingBottom)).coerceAtLeast(0)
    }

    private fun updateFollowing(value: Boolean) {
        if (following == value) return
        following = value
        onFollowingChanged(value)
    }

    private fun stopTrackedFling() {
        flinging = false
        nonTouchScrolling = false
        removeCallbacks(flingSettled)
        super.fling(0)
    }

    private fun scheduleScroll(toLatest: Boolean) {
        cancelDeferredScroll()
        val expectedScroll = scrollGeneration
        val expectedText = textGeneration
        val expectedTouch = touchGeneration
        fun current() = expectedScroll == scrollGeneration && expectedText == textGeneration &&
            expectedTouch == touchGeneration && !touching && !flinging && !nonTouchScrolling && following == toLatest
        post {
            if (!current()) return@post
            // Pre-draw runs after TextView has measured replacement partials and new segments.
            val observer = viewTreeObserver
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    if (pendingDraw === this) {
                        pendingDraw = null
                        pendingObserver = null
                    }
                    if (current()) {
                        val child = getChildAt(0)
                        val bottom = child?.bottom ?: 0
                        val maximum = (bottom - height + paddingBottom).coerceAtLeast(0)
                        val target = if (!toLatest) 0 else {
                            val offset = followTargetOffset
                            val textView = child as? TextView
                            val textLayout = textView?.layout
                            if (offset != null && textView != null && textLayout != null) {
                                val line = textLayout.getLineForOffset(offset.coerceIn(0, textView.text.length))
                                (textView.top + textView.totalPaddingTop + textLayout.getLineBottom(line) - height + paddingBottom)
                                    .coerceIn(0, maximum)
                            } else maximum
                        }
                        scrollTo(0, target)
                    }
                    return true
                }
            }
            pendingDraw = listener
            pendingObserver = observer
            observer.addOnPreDrawListener(listener)
            invalidate()
        }
    }

    private fun cancelDeferredScroll() {
        scrollGeneration++
        pendingDraw?.let { listener ->
            pendingObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        pendingDraw = null
        pendingObserver = null
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        changingLayout = true
        try { super.onLayout(changed, left, top, right, bottom) }
        finally { changingLayout = false }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        changingLayout = true
        try { super.onSizeChanged(width, height, oldWidth, oldHeight) }
        finally { changingLayout = false }
        if (following && !touching && !flinging && !nonTouchScrolling) scheduleScroll(toLatest = true)
    }

    override fun onDetachedFromWindow() {
        touching = false
        flinging = false
        nonTouchScrolling = false
        touchGeneration++
        cancelDeferredScroll()
        removeCallbacks(flingSettled)
        super.onDetachedFromWindow()
    }

    companion object { private const val FLING_QUIET_MS = 100L }
}
