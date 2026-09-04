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
import dev.octoshrimpy.quik.util.Preferences
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.withSign

class ControlShapeDrawable : Drawable() {

    companion object {
        /** Preferences.SHAPE_*; set once at startup and whenever the preference changes. */
        @Volatile var shape: Int = Preferences.SHAPE_SQUARE

        private const val ROUNDED_RADIUS = 0.22f   // of the shorter side
        private const val SQUIRCLE_N = 4.0         // superellipse exponent; One UI sits near here
        private const val SQUIRCLE_STEPS = 96

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
                    for (i in 0 until SQUIRCLE_STEPS) {
                        val t = 2.0 * Math.PI * i / SQUIRCLE_STEPS
                        val c = cos(t); val s = sin(t)
                        val x = cx + (abs(c).pow(2.0 / SQUIRCLE_N).withSign(c) * rx).toFloat()
                        val y = cy + (abs(s).pow(2.0 / SQUIRCLE_N).withSign(s) * ry).toFloat()
                        if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
                    }
                    into.close()
                }
                else -> into.addRect(rect, Path.Direction.CW)
            }
            return into
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val path = Path()
    private var tint: ColorStateList? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        build(shape, bounds, path)
    }

    override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)

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
