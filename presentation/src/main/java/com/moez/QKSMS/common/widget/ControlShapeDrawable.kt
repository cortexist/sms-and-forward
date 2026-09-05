/*
 * Part of QUIK. GPLv3 - see the project LICENSE.
 *
 * The one shape behind every avatar and round button, chosen at runtime.
 *
 * res/drawable/circle.xml used to be a 1000dp-radius rectangle referenced from
 * two dozen layouts. It is now `<drawable class="...ControlShapeDrawable"/>`, so
 * every one of those references draws whatever Preferences.controlShape says --
 * square (the Omarchy look), rounded square, circle, or a squircle like One UI's
 * icons -- without the layouts knowing. The framework builds XML drawables with
 * no Context, hence the static `shape`, kept in sync by QKApplication.
 *
 * Tint goes through setTintList/setTint, which is what View.backgroundTintList,
 * DrawableCompat and the app's setBackgroundTint all end up calling, so the
 * existing theme-colouring keeps working. getOutline lets the ripple mask and
 * clipping follow the same shape.
 */
package dev.octoshrimpy.quik.common.widget

import android.content.res.ColorStateList
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
import android.view.View
import dev.octoshrimpy.quik.util.Preferences
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min

class ControlShapeDrawable : Drawable() {

    companion object {
        // Every live instance, so a preference change reaches views that already exist:
        // recycler rows, the compose button and the compose screen all outlive a visit to
        // Settings and would otherwise keep the path they built when first laid out.
        private val live: MutableSet<ControlShapeDrawable> =
            Collections.newSetFromMap(WeakHashMap<ControlShapeDrawable, Boolean>())

        /** Preferences.SHAPE_*; set once at startup and whenever the preference changes. */
        @Volatile var shape: Int = Preferences.SHAPE_SQUARE
            set(value) {
                if (field == value) return
                field = value
                synchronized(live) { live.toList() }.forEach { it.shapeChanged() }
            }

        private const val ROUNDED_RADIUS = 0.22f   // of the shorter side
        // One UI's icon squircle, taken from Samsung's own SVG: four cubic Béziers, each from
        // one side's midpoint to the next, with both control points 70% of the way along the
        // edges (0.7 = 46.706 / 66.722 in the 134-unit source).
        private const val SQUIRCLE_K = 0.70f

        fun build(shape: Int, bounds: Rect, into: Path): Path {
            into.reset()
            val rect = RectF(bounds)
            when (shape) {
                Preferences.SHAPE_ROUND -> into.addOval(rect, Path.Direction.CW)
                Preferences.SHAPE_ROUNDED -> {
                    val r = min(rect.width(), rect.height()) * ROUNDED_RADIUS
                    into.addRoundRect(rect, r, r, Path.Direction.CW)
                }
                Preferences.SHAPE_SQUIRCLE -> {
                    val cx = rect.centerX(); val cy = rect.centerY()
                    val rx = rect.width() / 2f; val ry = rect.height() / 2f
                    val kx = rx * SQUIRCLE_K; val ky = ry * SQUIRCLE_K
                    into.moveTo(cx - rx, cy)
                    into.cubicTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
                    into.cubicTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
                    into.cubicTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
                    into.cubicTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
                    into.close()
                }
                else -> into.addRect(rect, Path.Direction.CW)
            }
            return into
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val path = Path()
    private var builtShape = -1
    private var tint: ColorStateList? = null

    /** A shape for this instance alone (an agent's avatar); null follows the global setting. */
    var shapeOverride: Int? = null
        set(value) { field = value; rebuild(); (callback as? View)?.invalidateOutline(); invalidateSelf() }

    private val effectiveShape: Int get() = shapeOverride ?: shape

    init {
        synchronized(live) { live.add(this) }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    private fun rebuild() {
        builtShape = effectiveShape
        build(builtShape, bounds, path)
    }

    /** Rebuild now and refresh the owner's cached outline: a view that clips to its
     *  outline (photo avatars) would otherwise keep the old shape until re-laid out. */
    private fun shapeChanged() {
        rebuild()
        (callback as? View)?.invalidateOutline()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (builtShape != effectiveShape) rebuild()   // the setting changed since this path was built
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
