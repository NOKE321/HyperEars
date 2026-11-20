package dev.hyperears.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import android.util.LruCache
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.HeadsetArtworkId

/** Only bundled, namespaced resources can cross into the native host. No paths or network URLs. */
internal class NativeArtworkLoader(context: Context) {
    private val resources = context.packageManager.getResourcesForApplication(ModuleContract.MODULE_PACKAGE)
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** 本地私有构建：把指定的 headset_ 前缀资源按帧号拼成帧动画。 */
    private val animatedFrameCounts = mapOf(
        "headset_generic_earbuds" to 24,   // 默认 TWS 耳机图 -> 帧动画
        "headset_freeclip_earbuds" to 24,
    )

    @Suppress("DiscouragedApi")
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun animatedFrames(id: String): Drawable? {
        val count = animatedFrameCounts[id] ?: return null
        val frames = ArrayList<Drawable>(count)
        for (i in 1..count) {
            val name = "%s_%02d".format(id, i)
            val res = resources.getIdentifier(name, "drawable", ModuleContract.MODULE_PACKAGE)
            if (res == 0) return null
            @Suppress("DEPRECATION")
            frames += resources.getDrawable(res, null)
        }
        return AutoStartAnimationDrawable().apply { addFrames(frames) }
    }

    @Suppress("DiscouragedApi")
    @android.annotation.SuppressLint("DiscouragedApi")
    fun load(id: HeadsetArtworkId, fallback: HeadsetArtworkId): Drawable {
        animatedFrames(id.value)?.let { return it }
        val bitmap = cache[id.value] ?: synchronized(cache) {
            cache[id.value] ?: run {
                fun decode(name: String): Bitmap? {
                    val resource = resources.getIdentifier(name, "drawable", ModuleContract.MODULE_PACKAGE)
                    if (resource == 0) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeResource(resources, resource, bounds)
                    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                    return BitmapFactory.decodeResource(resources, resource, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inScaled = false
                    })
                }
                requireNotNull(decode(id.value) ?: decode(fallback.value)).also { cache.put(id.value, it) }
            }
        }
        // A fresh drawable per native view prevents shared bounds/state between windows.
        return bitmap.toDrawable(resources)
    }
}
