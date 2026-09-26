package app.svetlo.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView

/** Scales the image to the view width and anchors it to the top, like a page preview. */
class TopCropImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ImageView(ctx, attrs) {
    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        val changed = super.setFrame(l, t, r, b)
        updateMatrix()
        return changed
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        updateMatrix()
    }

    private fun updateMatrix() {
        val d = drawable ?: return
        val w = width - paddingLeft - paddingRight
        if (w <= 0 || d.intrinsicWidth <= 0) return
        val s = w.toFloat() / d.intrinsicWidth
        imageMatrix = Matrix().apply { setScale(s, s) }
    }
}
