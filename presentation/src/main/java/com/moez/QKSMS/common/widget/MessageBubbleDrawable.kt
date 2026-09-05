/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * The message bubble background, with the corner geometry chosen by the "Message shape"
 * setting: sharp boxes (Omarchy) or QUIK's rounded bubbles (18dp corners, 4dp on the side
 * that joins the next bubble of a run). Each message_*.xml names its corner set through
 * app:bubbleCorners; the radii come from the static `style`, kept in step with the
 * preference by QKApplication, and live instances redraw when it changes.
 */
package dev.octoshrimpy.quik.common.widget

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.util.Preferences
import org.xmlpull.v1.XmlPullParser
import java.util.Collections
import java.util.WeakHashMap

class MessageBubbleDrawable : Drawable() {

    companion object {
        private val live: MutableSet<MessageBubbleDrawable> =
            Collections.newSetFromMap(WeakHashMap<MessageBubbleDrawable, Boolean>())

        @Volatile var style: Int = Preferences.BUBBLE_BOXES
            set(value) {
                if (field == value) return
                field = value
                synchronized(live) { live.toList() }.forEach { it.styleChanged() }
            }

        private const val BIG_DP = 18f
        private const val SMALL_DP = 4f
        private const val PAD_V_DP = 8f
        private const val PAD_H_DP = 12f

        // Corner sets, as (topLeft, topRight, bottomRight, bottomLeft) in units of "big" (true)
        // or "small" (false); the attr enum values index this table.
        private val CORNERS = arrayOf(
            booleanArrayOf(true, true, true, true),     // only
            booleanArrayOf(true, true, true, false),    // inFirst
            booleanArrayOf(false, true, true, false),   // inMiddle
            booleanArrayOf(false, true, true, true),    // inLast
            booleanArrayOf(true, true, false, true),    // outFirst
            booleanArrayOf(true, false, false, true),   // outMiddle
            booleanArrayOf(true, false, true, true))    // outLast
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val path = Path()
    private var corners = 0
    private var builtStyle = -1
    private var density = 1f
    private var tint: ColorStateList? = null

    init {
        synchronized(live) { live.add(this) }
    }

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
        val a = theme?.obtainStyledAttributes(attrs, R.styleable.MessageBubble, 0, 0)
            ?: r.obtainAttributes(attrs, R.styleable.MessageBubble)
        corners = a.getInt(R.styleable.MessageBubble_bubbleCorners, 0)
        a.recycle()
    }

    override fun getPadding(padding: Rect): Boolean {
        val v = (PAD_V_DP * density).toInt(); val h = (PAD_H_DP * density).toInt()
        padding.set(h, v, h, v)
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    private fun rebuild() {
        builtStyle = style
        path.reset()
        val rect = RectF(bounds)
        if (style == Preferences.BUBBLE_BOXES) {
            path.addRect(rect, Path.Direction.CW)
            return
        }
        val big = BIG_DP * density; val small = SMALL_DP * density
        val set = CORNERS[corners.coerceIn(0, CORNERS.size - 1)]
        val radii = FloatArray(8)
        for (i in 0 until 4) { val r = if (set[i]) big else small; radii[i * 2] = r; radii[i * 2 + 1] = r }
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    private fun styleChanged() {
        rebuild()
        (callback as? View)?.invalidateOutline()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (builtStyle != style) rebuild()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun getAlpha(): Int = paint.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getColorFilter(): ColorFilter? = paint.colorFilter
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTint(tintColor: Int) = setTintList(ColorStateList.valueOf(tintColor))
    override fun setTintList(tint: ColorStateList?) { this.tint = tint; applyTint() }
    override fun isStateful(): Boolean = tint?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean { applyTint(); return true }
    private fun applyTint() {
        val t = tint
        paint.color = t?.getColorForState(state, t.defaultColor) ?: Color.WHITE
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) outline.setPath(path) else @Suppress("DEPRECATION") outline.setConvexPath(path)
        outline.alpha = 1f
    }
}
