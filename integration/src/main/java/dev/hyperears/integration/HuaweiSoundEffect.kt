package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * HUAWEI 音效预设（智慧音频里的「音效」页）。
 *
 * 与降噪无关，是独立的 `2B 49` 指令：默认 / 运动增效 / 清晰人声。
 * 只有实测支持该指令的型号（如 FreeClip）才暴露这个特性。
 */
@Serializable
enum class HuaweiSoundEffect {
    @SerialName("standard")
    STANDARD,

    @SerialName("sport")
    SPORT,

    @SerialName("vocals")
    VOCALS,
}

internal fun HuaweiSoundEffect.toWireSoundEffect(): HuaweiFreebudsSppCodec.SoundEffect = when (this) {
    HuaweiSoundEffect.STANDARD -> HuaweiFreebudsSppCodec.SoundEffect.STANDARD
    HuaweiSoundEffect.SPORT -> HuaweiFreebudsSppCodec.SoundEffect.SPORT
    HuaweiSoundEffect.VOCALS -> HuaweiFreebudsSppCodec.SoundEffect.VOCALS
}

internal fun HuaweiFreebudsSppCodec.SoundEffect.toDomainSoundEffect(): HuaweiSoundEffect = when (this) {
    HuaweiFreebudsSppCodec.SoundEffect.STANDARD -> HuaweiSoundEffect.STANDARD
    HuaweiFreebudsSppCodec.SoundEffect.SPORT -> HuaweiSoundEffect.SPORT
    HuaweiFreebudsSppCodec.SoundEffect.VOCALS -> HuaweiSoundEffect.VOCALS
}

/** 协议确认过的音效选择，只由支持该指令的华为适配器发布。 */
@Serializable
@SerialName("huawei.sound_effect")
data class HuaweiSoundEffectFeatureState(
    val effect: HuaweiSoundEffect,
) : DeviceFeatureState {
    @Transient
    override val featureId: String = FEATURE_ID

    companion object {
        const val FEATURE_ID = "huawei.sound_effect"
    }
}

/** 华为私有的音效请求，独立于通用的降噪三态控制。 */
@Serializable
sealed interface HuaweiSoundEffectRequest : ControlRequest {
    @Serializable
    @SerialName("huawei.set_sound_effect")
    data class SetSoundEffect(
        val effect: HuaweiSoundEffect,
    ) : HuaweiSoundEffectRequest
}

object HuaweiMiLinkPresentationIds {
    /** 借用 MiLink 原生三态槽位呈现「默认 / 运动增效 / 清晰人声」。 */
    val SOUND_EFFECT = MiLinkCardPresentationId("huawei-sound-effect")
}
