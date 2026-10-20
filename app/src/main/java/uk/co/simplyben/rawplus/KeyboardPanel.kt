package uk.co.simplyben.rawplus

import android.content.Context
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout

/** A bounded IME panel that leaves framework window fitting and navigation controls intact. */
class KeyboardPanel(context: Context) : LinearLayout(context) {
    var expanded: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val baseHorizontal = Ui.dp(context, 12)
    private val baseVertical = Ui.dp(context, 8)
    private val collapsedHeight = Ui.dp(context, 420)
    private val hostContextGap = Ui.dp(context, 48)
    private val panelLocation = IntArray(2)
    private val windowLocation = IntArray(2)
    private var updatePosted = false
    private val updatePadding = Runnable {
        updatePosted = false
        if (isAttachedToWindow) updateSafePadding()
    }
    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener { schedulePaddingUpdate() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.paper(context))
        setPadding(baseHorizontal, baseVertical, baseHorizontal, baseVertical)
        setOnApplyWindowInsetsListener { _, insets ->
            schedulePaddingUpdate()
            insets
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePaddingUpdate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val available = if (mode == View.MeasureSpec.UNSPECIFIED) fallbackHeight()
            else View.MeasureSpec.getSize(heightMeasureSpec)
        val height = KeyboardPanelGeometry.height(
            expanded, collapsedHeight, available, hostContextGap,
            exact = mode == View.MeasureSpec.EXACTLY
        )
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(globalLayout)
        requestApplyInsets()
        schedulePaddingUpdate()
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalLayoutListener(globalLayout)
        removeCallbacks(updatePadding)
        updatePosted = false
        super.onDetachedFromWindow()
    }

    private fun fallbackHeight(): Int {
        return try {
            val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
            val top = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            ).top
            (metrics.bounds.height() - top).coerceAtLeast(1)
        } catch (_: RuntimeException) {
            // Finite fallback only. Normal IME measurement supplies an AT_MOST ceiling.
            resources.displayMetrics.heightPixels.coerceAtLeast(1)
        }
    }

    private fun schedulePaddingUpdate() {
        if (!isAttachedToWindow || updatePosted) return
        updatePosted = true
        post(updatePadding)
    }

    private fun updateSafePadding() {
        val decor = rootView
        if (width == 0 || height == 0 || decor.width == 0 || decor.height == 0) return
        // Root insets retain the original window geometry even if an ancestor consumed them.
        val insets = rootWindowInsets?.getInsets(
            WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout()
        ) ?: return
        getLocationInWindow(panelLocation)
        decor.getLocationInWindow(windowLocation)
        val extra = KeyboardPanelGeometry.residual(
            panel = PanelBounds(panelLocation[0], panelLocation[1], panelLocation[0] + width, panelLocation[1] + height),
            window = PanelBounds(windowLocation[0], windowLocation[1], windowLocation[0] + decor.width, windowLocation[1] + decor.height),
            insets = PanelSafeInsets(insets.left, insets.right, insets.bottom)
        )
        val left = baseHorizontal + extra.left
        val right = baseHorizontal + extra.right
        val bottom = baseVertical + extra.bottom
        if (paddingLeft != left || paddingTop != baseVertical || paddingRight != right || paddingBottom != bottom) {
            setPadding(left, baseVertical, right, bottom)
        }
    }
}

internal data class PanelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class PanelSafeInsets(val left: Int, val right: Int, val bottom: Int)

/** Pure calculations shared by measurement and the actual laid-out overlap check. */
internal object KeyboardPanelGeometry {
    fun height(expanded: Boolean, collapsed: Int, available: Int, hostGap: Int, exact: Boolean): Int {
        val ceiling = available.coerceAtLeast(0)
        if (exact) return ceiling
        val desired = if (expanded) maxOf(collapsed, ceiling - hostGap) else collapsed
        return desired.coerceIn(0, ceiling)
    }

    fun residual(panel: PanelBounds, window: PanelBounds, insets: PanelSafeInsets): PanelSafeInsets {
        val width = (panel.right - panel.left).coerceAtLeast(0)
        val height = (panel.bottom - panel.top).coerceAtLeast(0)
        return PanelSafeInsets(
            left = (window.left + insets.left - panel.left).coerceIn(0, width),
            right = (panel.right - (window.right - insets.right)).coerceIn(0, width),
            bottom = (panel.bottom - (window.bottom - insets.bottom)).coerceIn(0, height)
        )
    }
}
