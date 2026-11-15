package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

/**
 * A lightweight book-like page flip for the classic pager.
 *
 * Instead of sliding, the page being turned stays in place and rotates around its spine while the
 * page underneath is revealed. Only view render properties (translation, rotation, alpha, scale) are
 * changed, so with the hardware layers ViewPager applies during scrolls the image is never redrawn
 * and the animation runs on the render thread, cheap even on low-end GPUs.
 *
 * ViewPager positions: 0 is the current page, -1 the page before it in adapter order and 1 the one
 * after. [spineAtStart] says which of the two is the turning page: the one on the negative side for
 * left to right and vertical readers, the one on the positive side for right to left readers (whose
 * adapter is reversed). The pager must draw that side on top, see [reverseDrawingOrder].
 */
class BookFlipPageTransformer(
    private val isHorizontal: Boolean,
    private val spineAtStart: Boolean,
) : ViewPager.PageTransformer {

    /** Pass to [ViewPager.setPageTransformer] so the turning page is drawn above the revealed one. */
    val reverseDrawingOrder: Boolean
        get() = spineAtStart

    override fun transformPage(page: View, position: Float) {
        if (position <= -1f || position >= 1f || position == 0f) {
            reset(page)
            return
        }

        val width = page.width.toFloat()
        val height = page.height.toFloat()

        // Keep every page in place, cancelling ViewPager's slide.
        if (isHorizontal) {
            page.translationX = -position * width
            page.translationY = 0f
        } else {
            page.translationX = 0f
            page.translationY = -position * height
        }

        val isTurningPage = if (spineAtStart) position < 0f else position > 0f
        if (isTurningPage) {
            // 0 when flat, 1 when turned away completely.
            val progress = abs(position)
            page.scaleX = 1f
            page.scaleY = 1f
            if (progress >= 0.5f) {
                // Past edge-on: the page is now on the other side of the spine.
                page.alpha = 0f
                return
            }
            page.alpha = 1f
            page.cameraDistance = CAMERA_DISTANCE * page.resources.displayMetrics.density
            if (isHorizontal) {
                page.pivotX = if (spineAtStart) 0f else width
                page.pivotY = height / 2f
                page.rotationX = 0f
                page.rotationY = position * 180f
            } else {
                page.pivotX = width / 2f
                page.pivotY = 0f
                page.rotationY = 0f
                page.rotationX = -position * 180f
            }
        } else {
            // The page underneath: flat, slightly receded and shaded until it is uncovered.
            val covered = abs(position)
            page.rotationX = 0f
            page.rotationY = 0f
            page.alpha = 1f - covered * UNDER_PAGE_SHADE
            val scale = 1f - covered * UNDER_PAGE_SHRINK
            page.pivotX = width / 2f
            page.pivotY = height / 2f
            page.scaleX = scale
            page.scaleY = scale
        }
    }

    private fun reset(page: View) {
        page.translationX = 0f
        page.translationY = 0f
        page.rotationX = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.alpha = 1f
    }

    private companion object {
        /** Large enough that the lifted edge does not blow up in perspective. */
        const val CAMERA_DISTANCE = 12_000f
        const val UNDER_PAGE_SHADE = 0.45f
        const val UNDER_PAGE_SHRINK = 0.06f
    }
}
