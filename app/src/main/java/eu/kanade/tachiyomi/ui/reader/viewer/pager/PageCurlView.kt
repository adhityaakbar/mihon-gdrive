package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.hypot

/**
 * Draws a paper-like page curl over a horizontal pager while a page is being turned.
 *
 * The turning page is folded along a line that sweeps from its free edge towards the spine: the
 * part before the line stays flat, the part past it is mirrored over the line as the back of the
 * sheet, and the page underneath shows through behind it. Shadows along the fold sell the depth.
 *
 * The pages are drawn straight from their views onto this view's hardware canvas, so nothing is
 * copied into bitmaps; each frame is a few clipped display-list draws plus gradients.
 *
 * Geometry is worked out for a spine on the left with the bottom corner leading, and mirrored
 * horizontally when [spineAtStart] is false and vertically when [fromTop] is set.
 */
@SuppressLint("ViewConstructor")
class PageCurlView(
    context: Context,
    private val spineAtStart: Boolean,
) : View(context) {

    private var turning: View? = null
    private var under: View? = null

    /** 0 when the turning page lies flat, 1 when it is gone. */
    private var progress = 0f

    var backgroundColour: Int = Color.BLACK

    /** Lift the top corner instead of the bottom one, following where the finger went down. */
    var fromTop: Boolean = false

    private val frontPath = Path()
    private val flapPath = Path()
    private val pageRect = Path()
    private val reflect = Matrix()
    private val mirror = Matrix()
    private val tmpPath = Path()

    private val fillPaint = Paint()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        // Never takes touches: the pager below keeps handling the gesture.
        isClickable = false
        isFocusable = false
        visibility = GONE
    }

    val isCurling: Boolean
        get() = visibility == VISIBLE

    fun show(turning: View, under: View?, progress: Float) {
        this.turning = turning
        this.under = under
        this.progress = progress.coerceIn(0f, 1f)
        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        turning = null
        under = null
        if (visibility != GONE) visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        val turning = turning ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val p = progress
        // Fold line from the bottom edge (bx, h) to the top edge (tx, 0). It starts as a small fold
        // near the bottom right corner and sweeps past the spine, tilting less as it goes, so the
        // corner leads like a real sheet being turned.
        val bx = w - p * w * SWEEP
        val tx = bx + TILT * w * (1f - p)

        // Unit direction of the fold line (bottom to top) and the normal pointing to the flap side.
        val dx = tx - bx
        val dy = -h
        val len = hypot(dx, dy)
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux

        // Front: the part of the page on the spine side of the line (a large half-plane quad
        // clipped to the page).
        val far = (w + h) * 4f
        pageRect.rewind()
        pageRect.addRect(0f, 0f, w, h, Path.Direction.CW)
        frontPath.rewind()
        frontPath.moveTo(bx - ux * far, h - uy * far)
        frontPath.lineTo(bx + ux * far, h + uy * far)
        frontPath.lineTo(bx + ux * far - nx * far, h + uy * far - ny * far)
        frontPath.lineTo(bx - ux * far - nx * far, h - uy * far - ny * far)
        frontPath.close()
        frontPath.op(pageRect, Path.Op.INTERSECT)

        // Flap: the rest of the page, reflected over the fold line.
        flapPath.set(pageRect)
        flapPath.op(frontPath, Path.Op.DIFFERENCE)
        setReflection(reflect, bx, h, ux, uy)
        tmpPath.set(flapPath)
        tmpPath.transform(reflect)
        flapPath.set(tmpPath)
        flapPath.op(pageRect, Path.Op.INTERSECT)

        // The geometry above lifts the bottom corner of a page bound on the left; mirror it for
        // right-bound pages and for top-corner turns.
        mirror.reset()
        val sx = if (spineAtStart) 1f else -1f
        val sy = if (fromTop) -1f else 1f
        if (sx != 1f || sy != 1f) {
            mirror.setScale(sx, sy, w / 2f, h / 2f)
            frontPath.transform(mirror)
            flapPath.transform(mirror)
        }

        // 1. The page underneath, with a shadow cast by the lifted sheet along the fold.
        canvas.drawColor(backgroundColour)
        under?.draw(canvas)
        val foldMidX = (bx + tx) / 2f
        val foldMidY = h / 2f
        val shadowWidth = w * UNDER_SHADOW * (0.4f + p)
        canvas.save()
        canvas.concat(mirror)
        shadowPaint.shader = LinearGradient(
            foldMidX, foldMidY,
            foldMidX + nx * shadowWidth, foldMidY + ny * shadowWidth,
            Color.argb(110, 0, 0, 0), Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, shadowPaint)
        canvas.restore()

        // 2. The flat part of the turning page, darkening slightly towards the bend.
        canvas.save()
        canvas.clipPath(frontPath)
        canvas.drawColor(backgroundColour)
        turning.draw(canvas)
        canvas.concat(mirror)
        val bend = w * FRONT_SHADE
        shadowPaint.shader = LinearGradient(
            foldMidX - nx * bend, foldMidY - ny * bend,
            foldMidX, foldMidY,
            Color.TRANSPARENT, Color.argb(70, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, shadowPaint)
        canvas.restore()

        // 3. The back of the sheet: mirrored print showing faintly through paper, shaded at the fold.
        if (!flapPath.isEmpty) {
            canvas.save()
            canvas.clipPath(flapPath)
            canvas.concat(mirror)
            canvas.concat(reflect)
            canvas.concat(mirror)
            canvas.drawColor(backgroundColour)
            turning.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.clipPath(flapPath)
            canvas.concat(mirror)
            val flapDepth = w * FLAP_SHADE
            backPaint.shader = LinearGradient(
                foldMidX, foldMidY,
                foldMidX - nx * flapDepth, foldMidY - ny * flapDepth,
                intArrayOf(Color.argb(235, 200, 200, 196), Color.argb(225, 244, 243, 239), Color.argb(235, 226, 225, 220)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, backPaint)
            canvas.restore()
        }
    }

    /** Reflection over the line through ([px], [py]) with unit direction ([ux], [uy]). */
    private fun setReflection(matrix: Matrix, px: Float, py: Float, ux: Float, uy: Float) {
        val a = ux * ux - uy * uy
        val b = 2f * ux * uy
        // x' = a*(x-px) + b*(y-py) + px ; y' = b*(x-px) - a*(y-py) + py
        matrix.setValues(
            floatArrayOf(
                a, b, px - a * px - b * py,
                b, -a, py - b * px + a * py,
                0f, 0f, 1f,
            ),
        )
    }

    private companion object {
        /** How far the fold travels across the page, in page widths, over a full turn. */
        const val SWEEP = 1.35f

        /** How far ahead the top of the fold runs at the start, in page widths. */
        const val TILT = 0.35f

        const val UNDER_SHADOW = 0.12f
        const val FRONT_SHADE = 0.06f
        const val FLAP_SHADE = 0.25f
    }
}
