package dev.hyperears.presentation

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.DrawableWrapper

/**
 * 包装 AnimatedImageDrawable：宿主只调用 setImageDrawable()，不会主动 start()，
 * 因此在这里跟随可见性自动播放/暂停。
 */
internal class AutoStartDrawableWrapper(
    private val animated: AnimatedImageDrawable,
) : DrawableWrapper(animated) {

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        runCatching {
            if (visible) animated.start() else animated.stop()
        }
        return changed
    }
}
