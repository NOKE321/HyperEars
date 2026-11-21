package dev.hyperears.presentation

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.DrawableWrapper
import java.io.File

/**
 * 包装 AnimatedImageDrawable：宿主只调用 setImageDrawable()，这里负责让动画一直播下去。
 *
 * 关键点：宿主每次刷新电量都会重建/重新绑定视图，detach 时会 setVisible(false)。
 * 如果跟着宿主停一下再开，动画就会卡一下甚至从头放（表现为「抽风」）。
 * 所以这里可见就 start()，不可见不停；只有弹窗真正关掉时才由 stop() 统一停止。
 */
internal class AutoStartDrawableWrapper(
    private val animated: AnimatedImageDrawable,
    @Suppress("unused") private val filesDir: File?,
) : DrawableWrapper(animated) {

    init {
        runCatching {
            animated.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            animated.start()
        }
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) runCatching { animated.start() }
        return changed
    }

    /** 弹窗关闭时调用，避免动画在后台空转。 */
    fun stop() {
        runCatching { animated.stop() }
    }

    private companion object {
        const val SCALE_NAME = "artscale.txt"
        const val DEFAULT_SCALE = 1.8f
    }
}
