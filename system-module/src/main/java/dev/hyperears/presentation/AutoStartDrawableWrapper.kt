package dev.hyperears.presentation

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.DrawableWrapper
import java.io.File

/**
 * 包装 AnimatedImageDrawable：
 * 1) 宿主只调用 setImageDrawable()，这里跟随可见性自动播放/暂停；
 * 2) 放大 intrinsic 尺寸，让原生弹窗里的图更大。
 *
 * 放大倍数读取模块私有目录里的 arscale 文件（root 可写，无需重编译）：
 *   su -c 'echo 2.0 > /data/user/0/dev.hyperears/files/artscale.txt'
 * 默认 1.8。
 */
internal class AutoStartDrawableWrapper(
    private val animated: AnimatedImageDrawable,
    private val filesDir: File?,
) : DrawableWrapper(animated) {

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        runCatching { if (visible) animated.start() else animated.stop() }
        return changed
    }

    override fun getIntrinsicWidth(): Int = (super.getIntrinsicWidth() * scale()).toInt()

    override fun getIntrinsicHeight(): Int = (super.getIntrinsicHeight() * scale()).toInt()

    private fun scale(): Float = runCatching {
        val file = File(filesDir ?: return DEFAULT_SCALE, SCALE_NAME)
        if (!file.isFile) return DEFAULT_SCALE
        file.readText().trim().toFloatOrNull()?.coerceIn(0.5f, 4.0f) ?: DEFAULT_SCALE
    }.getOrDefault(DEFAULT_SCALE)

    private companion object {
        const val SCALE_NAME = "artscale.txt"
        const val DEFAULT_SCALE = 1.8f
    }
}
