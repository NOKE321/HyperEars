package dev.hyperears.presentation

import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable

/**
 * 帧动画 Drawable：宿主只调用 ImageView.setImageDrawable()，不会主动 start()，
 * 因此在这里重写 setVisible()，被挂到可见窗口时自动播放、离开时停止。
 *
 * 素材来源：华为智慧音频资源包（本地私有使用，勿提交上游）。
 */
internal class AutoStartAnimationDrawable(
    private val frameDurationMs: Int = 293,
    private val oneShot: Boolean = false,
) : AnimationDrawable() {

    private var built = false

    fun addFrames(frames: List<Drawable>) {
        if (built) return
        frames.forEach { addFrame(it, frameDurationMs) }
        isOneShot = oneShot
        built = true
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!built) return changed
        if (visible) {
            if (!isRunning) start()
        } else if (isRunning) {
            stop()
        }
        return changed
    }
}
