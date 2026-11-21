package dev.hyperears.presentation

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.DrawableWrapper

/**
 * 包装 AnimatedImageDrawable：
 * 1) 宿主只调用 setImageDrawable()，这里跟随可见性自动播放/暂停；
 * 2) 放大 intrinsic 尺寸，让原生弹窗里的图更大。
 *
 * 放大倍数可通过系统属性调，不必重新编译：
 *   adb/su: setprop persist.hyperears.artscale 2.2
 * 默认 1.8。
 */
internal class AutoStartDrawableWrapper(
    private val animated: AnimatedImageDrawable,
) : DrawableWrapper(animated) {

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        runCatching { if (visible) animated.start() else animated.stop() }
        return changed
    }

    override fun getIntrinsicWidth(): Int = (super.getIntrinsicWidth() * scale()).toInt()

    override fun getIntrinsicHeight(): Int = (super.getIntrinsicHeight() * scale()).toInt()

    private fun scale(): Float = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        ((get.invoke(null, PROP_KEY, DEFAULT_SCALE) as String).toFloatOrNull() ?: DEFAULT_SCALE)
            .coerceIn(0.5f, 4.0f)
    }.getOrDefault(DEFAULT_SCALE)

    private companion object {
        const val PROP_KEY = "persist.hyperears.artscale"
        const val DEFAULT_SCALE = 1.8f
    }
}
