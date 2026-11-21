package dev.hyperears.hook

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.hyperears.R
import dev.hyperears.bridge.ModuleContract
import dev.hyperears.integration.ControlOwnership
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.HuaweiMiLinkPresentationIds
import dev.hyperears.integration.HuaweiSoundEffect
import dev.hyperears.integration.HuaweiSoundEffectFeatureState
import dev.hyperears.integration.HuaweiSoundEffectRequest
import dev.hyperears.integration.MiLinkCardPresentationId
import dev.hyperears.integration.NoiseMode
import java.lang.ref.WeakReference

/**
 * 把「默认 / 运动增效 / 清晰人声」三个音效挂到 MiLink 耳机卡片的三态槽位上。
 *
 * FreeClip 没有降噪，所以借用的仍是 MiLink 原生三态卡片：把三件原生 item 克隆一份、
 * 只改文案和图标，点按时发 `2B 49` 音效指令；解绑时原样还原，不动宿主任何方法或隐藏字段。
 */
internal object HuaweiSoundEffectMiLinkCardAdapter : MiLinkCardAdapter {
    override val presentationId: MiLinkCardPresentationId = HuaweiMiLinkPresentationIds.SOUND_EFFECT
    override val nativeSurface: MiLinkNativeCardSurface =
        MiLinkNativeCardSurface.ANC_THREE_STATE

    override fun nativeSurfaceNoiseMode(state: EarbudState): NoiseMode? = NoiseMode.ANC

    override fun bind(
        root: View,
        address: String,
        environment: MiLinkCardEnvironment,
    ): MiLinkCardBinding? {
        val host = resolveNativeHost(root)
            ?: return skipped(address, "native-anc-card-unavailable")
        val iconResources = runCatching {
            root.context.packageManager.getResourcesForApplication(ModuleContract.MODULE_PACKAGE)
        }.onFailure {
            ModuleLog.warn(COMPONENT, "Huawei sound-effect resources unavailable; using host icons", it)
        }.getOrNull()
        val items = HuaweiSoundEffect.entries.mapIndexed { index, effect ->
            val source = host.sources[index % host.sources.size]
            host.createItem(root, source, EFFECT_LABELS.getValue(effect), effectIcon(iconResources, effect))
                ?: return skipped(address, "sound-effect-item-construction-failed")
        }.onEachIndexed { index, item ->
            if (index >= host.sources.size) item.id = View.generateViewId()
        }

        val binding = Binding(host, items, address, environment)
        if (!host.install(SOUND_EFFECT_LABEL, items)) return skipped(address, "native-container-detached")
        items.forEachIndexed { index, item ->
            item.setOnClickListener { binding.onEffectClicked(HuaweiSoundEffect.entries[index]) }
        }
        ModuleLog.debug(
            COMPONENT,
            "bound Huawei sound-effect card layout=${host.generation} " +
                "address=${maskBluetoothAddress(address)}",
        )
        return binding
    }

    private fun skipped(address: String, reason: String): MiLinkCardBinding? {
        ModuleLog.debug(
            COMPONENT,
            "skipped Huawei sound-effect card reason=$reason " +
                "address=${maskBluetoothAddress(address)}",
        )
        return null
    }

    private class Binding(
        private val host: NativeAncHost,
        items: List<View>,
        private val address: String,
        private val environment: MiLinkCardEnvironment,
    ) : MiLinkCardBinding {
        private val items = items.map(::WeakReference)
        private var lastRendered: HuaweiSoundEffectCardPolicy.CardState? = null
        /** 耳机通常不回读音效，点过之后以本地选择为准。 */
        private var local: HuaweiSoundEffect? = null

        override fun render(state: EarbudState) {
            val views = items.mapNotNull(WeakReference<View>::get)
            if (views.size != this.items.size) return
            val projected = HuaweiSoundEffectCardPolicy.render(state).let { it.copy(effect = it.effect ?: local) }
            views.forEachIndexed { index, view ->
                val selected = HuaweiSoundEffect.entries[index] == projected.effect
                view.setNativeChoiceEnabled(projected.enabled)
                view.setSelectedTree(selected)
            }
            if (lastRendered != projected) {
                lastRendered = projected
                ModuleLog.debug(
                    COMPONENT,
                    "rendered Huawei sound-effect card enabled=${projected.enabled} " +
                        "effect=${projected.effect} address=${maskBluetoothAddress(address)}",
                )
            }
        }

        fun onEffectClicked(effect: HuaweiSoundEffect) {
            val current = environment.stateProvider(address)
            val state = HuaweiSoundEffectCardPolicy.render(current)
            if (!state.enabled) return
            if (state.effect == effect) return
            local = effect
            environment.controlSender(
                address,
                HuaweiSoundEffectRequest.SetSoundEffect(effect),
            )
        }

        override fun unbind() {
            items.mapNotNull(WeakReference<View>::get).forEach { item ->
                item.setOnClickListener(null)
            }
            host.restore()
        }
    }

    private class NativeAncHost(
        title: TextView?,
        row: NativeAncModeRow,
    ) {
        private data class OriginalItem(
            val view: View,
            val index: Int,
            val layoutParams: ViewGroup.LayoutParams,
            val visibility: Int,
        )

        private val layout = row.layout
        val generation: String = layout.logName
        val sources: List<View> = row.items

        private val title = title?.let(::WeakReference)
        private val container = WeakReference(row.container)
        private val originalTitle = title?.text
        private val originals = row.items.map { item ->
            OriginalItem(
                view = item,
                index = row.container.indexOfChild(item),
                layoutParams = item.layoutParams,
                visibility = item.visibility,
            )
        }

        private val replacements = mutableListOf<WeakReference<View>>()

        fun createItem(root: View, source: View, label: String, icon: Drawable?): View? {
            val item = layout.createItem(root, source, label) ?: return null
            val drawable = icon ?: return item
            (item.findMiLinkView(layout.itemIconId) as? ImageView)?.setImageDrawable(drawable)
            return item
        }

        fun install(titleText: String, items: List<View>): Boolean {
            val container = container.get() ?: return false
            title?.get()?.text = titleText
            originals.sortedByDescending(OriginalItem::index).forEach { original ->
                if (original.view.parent === container) container.removeView(original.view)
            }
            val insertion = originals.minOfOrNull(OriginalItem::index)
                ?.coerceIn(0, container.childCount)
                ?: container.childCount
            items.forEachIndexed { offset, item ->
                container.addView(item, insertion + offset)
                replacements += WeakReference(item)
            }
            return true
        }

        fun restore() {
            val container = container.get() ?: return
            replacements.mapNotNull(WeakReference<View>::get).forEach { replacement ->
                if (replacement.parent === container) container.removeView(replacement)
            }
            originals.sortedBy(OriginalItem::index).forEach { original ->
                if (original.view.parent == null) {
                    original.view.layoutParams = original.layoutParams
                    original.view.visibility = original.visibility
                    container.addView(
                        original.view,
                        original.index.coerceIn(0, container.childCount),
                    )
                }
            }
            title?.get()?.text = originalTitle
            replacements.clear()
        }
    }

    private fun resolveNativeHost(root: View): NativeAncHost? =
        listOf(NativeAncModeLayout.SELECT_CARD, NativeAncModeLayout.ORIGINAL).firstNotNullOfOrNull { layout ->
            val row = NativeAncModeRow.resolve(root, layout) ?: return@firstNotNullOfOrNull null
            val title = root.findMiLinkView(layout.titleId) as? TextView
            NativeAncHost(title, row)
        }

    private fun effectIcon(resources: Resources?, effect: HuaweiSoundEffect): Drawable? = runCatching {
        resources ?: return@runCatching null
        ResourcesCompat.getDrawable(resources, EFFECT_ICONS.getValue(effect), null)
    }.getOrElse {
        ModuleLog.warn(COMPONENT, "Huawei effect icon unavailable; using the host icon", it)
        null
    }

    private fun View.setNativeChoiceEnabled(enabled: Boolean) {
        isEnabled = enabled
        isClickable = enabled
        alpha = if (enabled) ENABLED_ALPHA else DISABLED_ALPHA
    }

    private fun View.setSelectedTree(selected: Boolean) {
        isSelected = selected
        if (this !is ViewGroup) return
        for (index in 0 until childCount) getChildAt(index).setSelectedTree(selected)
    }

    private val COMPONENT = "MiLinkUi"
    private val SOUND_EFFECT_LABEL = "音效"
    private val EFFECT_LABELS = mapOf(
        HuaweiSoundEffect.STANDARD to "默认",
        HuaweiSoundEffect.SPORT to "运动增效",
        HuaweiSoundEffect.VOCALS to "清晰人声",
    )
    private val EFFECT_ICONS = mapOf(
        HuaweiSoundEffect.STANDARD to R.drawable.ic_flos2_effect_standard,
        HuaweiSoundEffect.SPORT to R.drawable.ic_flos2_effect_swimming,
        HuaweiSoundEffect.VOCALS to R.drawable.ic_flos2_effect_vocals,
    )
    private const val ENABLED_ALPHA = 1.0f
    private const val DISABLED_ALPHA = 0.45f
}

internal object HuaweiSoundEffectCardPolicy {
    data class CardState(
        val effect: HuaweiSoundEffect?,
        val enabled: Boolean,
    )

    fun render(state: EarbudState): CardState = CardState(
        // 没有回读时按「默认」呈现，点过之后由卡片自身记录。
        effect = state.features.get<HuaweiSoundEffectFeatureState>()?.effect
            ?: HuaweiSoundEffect.STANDARD,
        enabled = state.sessionActive && state.privateChannelConnected && state.handshakeAccepted &&
            state.lifecycle.controlOwnership == ControlOwnership.MODULE,
    )

    fun request(state: EarbudState, effect: HuaweiSoundEffect): HuaweiSoundEffect? {
        val current = render(state)
        if (!current.enabled || current.effect == effect) return null
        return effect
    }
}
