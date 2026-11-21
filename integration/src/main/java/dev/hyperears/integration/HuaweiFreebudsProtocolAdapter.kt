package dev.hyperears.integration

import dev.hyperears.protocol.huawei.HuaweiFreebudsSppCodec

internal data class HuaweiProtocolProfile(
    val noiseModes: Set<NoiseMode>,
    val extension: HuaweiProtocolExtension? = null,
) {
    val noiseTelemetryEnabled: Boolean get() = noiseModes.isNotEmpty()

    init {
        require(NoiseMode.WIND !in noiseModes) {
            "Huawei FreeBuds SPP does not define a wind-noise mode"
        }
        // 音效一类扩展与降噪无关，只要扩展自己声明了特性就可以挂在无降噪型号上。
        require(extension == null || noiseTelemetryEnabled || extension.featureIds.isNotEmpty()) {
            "Huawei protocol extensions require a noise-capable profile or explicit features"
        }
    }
}

/** Optional model-specific state and control behavior layered over the shared Huawei session. */
internal interface HuaweiProtocolExtension {
    val featureIds: Set<String>

    fun acceptsState(state: DeviceFeatureState): Boolean

    fun supportsRequest(adapter: EarbudAdapter, request: ControlRequest): Boolean

    fun encode(request: ControlRequest): ByteArray?

    fun state(mode: HuaweiFreebudsSppCodec.NoiseMode, level: Int?): DeviceFeatureState?

    fun controlPolicy(request: ControlRequest): ControlExecutionPolicy? = null

    fun readback(request: ControlRequest): List<ByteArray> = emptyList()

    /** 非降噪状态的扩展上报（例如音效选择），由具体型号解析。 */
    fun state(frame: HuaweiFreebudsSppCodec.Frame): DeviceFeatureState? = null

    /** 扩展特性的状态查询帧；返回空表示走通用降噪查询。 */
    fun query(featureId: String): List<ByteArray> = emptyList()
}

internal abstract class HuaweiFreebudsProtocolAdapter(
    endpointPrefix: String,
    channelNumbers: List<Int>,
    private val profile: HuaweiProtocolProfile,
) : StandardEarbudAdapter() {
    override val privateProtocolRequired: Boolean = true
    override val transportReadiness: TransportReadiness = TransportReadiness.PROTOCOL_HANDSHAKE
    override val transports: List<EarbudTransportSpec> = channelNumbers.map { channel ->
        RfcommEndpointSpec.Channel(
            number = channel,
            id = "$endpointPrefix-channel-$channel",
        )
    }
    override val controlApps: List<ControlAppSpec> = listOf(
        ControlAppCatalog.huaweiSmartAudio,
        ControlAppCatalog.huaweiAiLife,
    )

    override val featureStateContract: DeviceFeatureStateContract =
        if (profile.extension != null) {
            StandardDeviceFeatureStateContract.extending { _, state ->
                profile.extension.acceptsState(state)
            }
        } else {
            StandardDeviceFeatureStateContract
        }

    override val controlRequestContract: ControlRequestContract =
        if (profile.extension != null) {
            StandardControlRequestContract.extending { adapter, request ->
                profile.extension.supportsRequest(adapter, request)
            }
        } else {
            StandardControlRequestContract
        }

    override fun createProtocolSession(): ProtocolSession = HuaweiFreebudsProtocolSession(profile)

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy =
        profile.extension?.controlPolicy(request) ?: super.controlPolicy(request)

    override fun onInitialProtocolUnavailable(): InitialProtocolFailureResolution =
        InitialProtocolFailureResolution.KeepDormant
}

/** HUAWEI FreeBuds 4 profile: channel 1 with ANC/off and no transparency or depth controls. */
internal class HuaweiFreeBuds4Adapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freebuds-4",
    channelNumbers = listOf(1),
    profile = FREEBUDS_4_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds 4"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freebuds-4"
        private val MODEL_NAMES = setOf("huaweifreebuds4", "freebuds4")
    }
}

/** HUAWEI FreeClip 2: hardware-verified component battery without noise control. */
internal class HuaweiFreeClip2Adapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freeclip-2",
    channelNumbers = listOf(1),
    profile = FREECLIP_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeClip 2"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freeclip-2"
        private val MODEL_NAMES = setOf("huaweifreeclip2", "freeclip2")
    }
}

internal class HuaweiFreebudsFamilyAdapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freebuds-family",
    channelNumbers = listOf(1, 16),
    profile = HUAWEI_FAMILY_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeBuds protocol family"
    override val resolution: AdapterResolution = AdapterResolution.FAMILY_MATCH

    override fun matches(identity: EarbudIdentity): Boolean {
        if (!super.matches(identity)) return false
        val name = normalizeDeviceName(identity.deviceName.orEmpty())
        return HUAWEI_AUDIO_PREFIXES.any(name::startsWith)
    }

    companion object {
        const val ID = "huawei-freebuds-family"
        private val HUAWEI_AUDIO_PREFIXES = setOf(
            "huaweifreebuds",
            "freebuds",
            "huaweifreeclip",
            "freeclip",
            "huaweifreelace",
            "freelace",
        )
    }
}

private class HuaweiFreebudsProtocolSession(
    private val profile: HuaweiProtocolProfile,
) : ProtocolSession {
    private val decoder = HuaweiFreebudsSppCodec.Decoder()
    private var handshakePublished = false
    private var pendingNoiseRefresh = false

    override fun initialReadCommands(): List<ByteArray> = telemetryQueries()

    override fun encode(request: ControlRequest): List<ByteArray> = when (request) {
        StandardControlRequest.Refresh -> telemetryQueries()
        is StandardControlRequest.SetNoiseMode ->
            request.mode
                .takeIf(profile.noiseModes::contains)
                ?.let { listOf(HuaweiFreebudsSppCodec.noiseModeCommand(it.toHuaweiWireMode())) }
                .orEmpty()

        else -> profile.extension?.encode(request)?.let(::listOf).orEmpty()
    }

    override fun query(request: TelemetryQuery): List<ByteArray> = when (request) {
        TelemetryQuery.RefreshAll -> telemetryQueries()
        is TelemetryQuery.RefreshFeature ->
            profile.extension?.query(request.featureId)?.takeIf { it.isNotEmpty() }
                ?: when {
                    request.featureId == BatteryFeatureState.FEATURE_ID ->
                        listOf(HuaweiFreebudsSppCodec.queryBattery)

                    request.featureId == NoiseModeFeatureState.FEATURE_ID ||
                        request.featureId in profile.extension?.featureIds.orEmpty() ->
                        listOf(HuaweiFreebudsSppCodec.queryNoiseState)
                            .takeIf { profile.noiseTelemetryEnabled }
                            .orEmpty()

                    else -> emptyList()
                }
    }

    override fun readback(request: ControlRequest): List<ByteArray> = when (request) {
        is StandardControlRequest.SetNoiseMode ->
            listOf(HuaweiFreebudsSppCodec.queryNoiseState)
                .takeIf { profile.noiseTelemetryEnabled }
                .orEmpty()

        else -> profile.extension?.readback(request).orEmpty()
    }

    override fun drainImmediateCommands(): List<ByteArray> {
        if (!pendingNoiseRefresh) return emptyList()
        pendingNoiseRefresh = false
        return listOf(HuaweiFreebudsSppCodec.queryNoiseState)
    }

    override fun offer(bytes: ByteArray): List<ProtocolEvent> = buildList {
        decoder.offer(bytes).forEach { frameBytes ->
            val frame = HuaweiFreebudsSppCodec.parseFrame(frameBytes) ?: return@forEach
            if (frame.command == HuaweiFreebudsSppCodec.CMD_NOISE_CHANGE_NOTIFY) {
                if (profile.noiseTelemetryEnabled) pendingNoiseRefresh = true
                return@forEach
            }

            HuaweiFreebudsSppCodec.parseBatteryFrame(frameBytes)?.let { battery ->
                add(
                    ProtocolEvent.FeatureStateChanged(
                        BatteryFeatureState(
                            EarbudBattery(
                                left = BatteryReading(battery.leftPercent, charging = false),
                                right = BatteryReading(battery.rightPercent, charging = false),
                                case = BatteryReading(battery.casePercent, charging = false),
                                overall = BatteryReading(
                                    battery.globalPercent,
                                    charging = battery.isCharging,
                                ),
                            ),
                        ),
                    ),
                )
                add(ProtocolEvent.CapabilitiesIdentified(battery = true))
                publishHandshakeIfNeeded()
                return@forEach
            }

            profile.extension?.state(frame)?.let { add(ProtocolEvent.FeatureStateChanged(it)) }

            if (profile.noiseTelemetryEnabled) {
                HuaweiFreebudsSppCodec.parseNoiseState(frameBytes)?.let { state ->
                    val mode = state.mode.toDomainMode()
                    if (mode in profile.noiseModes) {
                        add(ProtocolEvent.FeatureStateChanged(NoiseModeFeatureState(mode)))
                        profile.extension?.state(state.mode, state.level)?.let { extensionState ->
                            add(ProtocolEvent.FeatureStateChanged(extensionState))
                        }
                        add(
                            ProtocolEvent.CapabilitiesIdentified(
                                battery = false,
                                noiseModes = profile.noiseModes,
                            ),
                        )
                        publishHandshakeIfNeeded()
                    }
                }
            }
        }
    }

    override fun reset() {
        decoder.reset()
        handshakePublished = false
        pendingNoiseRefresh = false
    }

    private fun telemetryQueries(): List<ByteArray> = buildList {
        add(HuaweiFreebudsSppCodec.queryBattery)
        if (profile.noiseTelemetryEnabled) add(HuaweiFreebudsSppCodec.queryNoiseState)
        profile.extension?.let { extension ->
            extension.featureIds.forEach { addAll(extension.query(it)) }
        }
    }

    private fun MutableList<ProtocolEvent>.publishHandshakeIfNeeded() {
        if (handshakePublished) return
        handshakePublished = true
        add(ProtocolEvent.HandshakeAccepted)
    }

    private fun HuaweiFreebudsSppCodec.NoiseMode.toDomainMode(): NoiseMode = when (this) {
        HuaweiFreebudsSppCodec.NoiseMode.ANC -> NoiseMode.ANC
        HuaweiFreebudsSppCodec.NoiseMode.OFF -> NoiseMode.OFF
        HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY -> NoiseMode.TRANSPARENCY
    }
}

internal fun NoiseMode.toHuaweiWireMode(): HuaweiFreebudsSppCodec.NoiseMode = when (this) {
    NoiseMode.ANC -> HuaweiFreebudsSppCodec.NoiseMode.ANC
    NoiseMode.OFF -> HuaweiFreebudsSppCodec.NoiseMode.OFF
    NoiseMode.TRANSPARENCY -> HuaweiFreebudsSppCodec.NoiseMode.TRANSPARENCY
    NoiseMode.WIND -> error("Huawei FreeBuds SPP does not define a wind-noise mode")
}

private val FREEBUDS_4_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF),
)

private val HUAWEI_FAMILY_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
)

private val FREECLIP_PROFILE = HuaweiProtocolProfile(
    noiseModes = emptySet(),
)

/**
 * HUAWEI FreeClip（初代）：没有降噪，但支持 0x2B49 音效切换。
 *
 * 音效是独立特性，因此只挂扩展、不声明任何降噪模式；MiLink 卡片通过
 * [HuaweiMiLinkPresentationIds.SOUND_EFFECT] 借用原生三态槽位呈现三个音效。
 */
internal class HuaweiFreeClipAdapter : HuaweiFreebudsProtocolAdapter(
    endpointPrefix = "huawei-freeclip",
    channelNumbers = listOf(1),
    profile = FREECLIP_SOUND_PROFILE,
) {
    override val id: String = ID
    override val displayName: String = "HUAWEI FreeClip"
    override val resolution: AdapterResolution = AdapterResolution.EXACT_MATCH

    /**
     * 呈现 id 必须等到协议证据（电量帧）之后才给出：初始注册时它是 null，
     * 否则会触发 EarbudAdapterRegistry「初始私有适配器必须锁定厂商能力」的断言，
     * 直接让蓝牙进程初始化崩掉、耳机连不上。
     */
    override val miLinkCardPresentationId: MiLinkCardPresentationId?
        get() = HuaweiMiLinkPresentationIds.SOUND_EFFECT.takeIf {
            runtimeState().features.get<BatteryFeatureState>() != null
        }

    override fun matches(identity: EarbudIdentity): Boolean =
        super.matches(identity) &&
            normalizeDeviceName(identity.deviceName.orEmpty()) in MODEL_NAMES

    companion object {
        const val ID = "huawei-freeclip"
        private val MODEL_NAMES = setOf("huaweifreeclip", "freeclip")
    }
}

private val SOUND_EFFECT_EXTENSION = object : HuaweiProtocolExtension {
    override val featureIds: Set<String> = setOf(HuaweiSoundEffectFeatureState.FEATURE_ID)

    override fun acceptsState(state: DeviceFeatureState): Boolean =
        state is HuaweiSoundEffectFeatureState

    override fun supportsRequest(adapter: EarbudAdapter, request: ControlRequest): Boolean =
        // FreeClip 只回 ACK 不回读音效，因此不能用「已有特性状态」当准入条件。
        request is HuaweiSoundEffectRequest.SetSoundEffect && adapter.id == HuaweiFreeClipAdapter.ID

    override fun encode(request: ControlRequest): ByteArray? =
        (request as? HuaweiSoundEffectRequest.SetSoundEffect)?.let {
            HuaweiFreebudsSppCodec.soundEffectCommand(it.effect.toWireSoundEffect())
        }

    override fun state(
        mode: HuaweiFreebudsSppCodec.NoiseMode,
        level: Int?,
    ): DeviceFeatureState? = null

    override fun state(frame: HuaweiFreebudsSppCodec.Frame): DeviceFeatureState? {
        val effect = HuaweiFreebudsSppCodec.parseSoundEffectState(frame.bytes) ?: return null
        return HuaweiSoundEffectFeatureState(effect.toDomainSoundEffect())
    }

    override fun query(featureId: String): List<ByteArray> =
        if (featureId == HuaweiSoundEffectFeatureState.FEATURE_ID) {
            listOf(HuaweiFreebudsSppCodec.querySoundEffect)
        } else {
            emptyList()
        }

    override fun controlPolicy(request: ControlRequest): ControlExecutionPolicy? =
        if (request is HuaweiSoundEffectRequest.SetSoundEffect) {
            // 耳机只回 ACK 不回读，改完用 2B 4A 回读一次确认。
            ControlExecutionPolicy(confirmation = ControlConfirmationPolicy.DEVICE_REPORT)
        } else {
            null
        }

    override fun readback(request: ControlRequest): List<ByteArray> =
        if (request is HuaweiSoundEffectRequest.SetSoundEffect) {
            listOf(HuaweiFreebudsSppCodec.querySoundEffect)
        } else {
            emptyList()
        }
}

/**
 * 音效卡片借用 MiLink 的降噪三态槽位，因此这里必须声明降噪模式，
 * 否则整块「噪声控制」卡片根本不会被创建，三个音效也就没有落点。
 * 实际点击由卡片适配器接管，不会下发任何降噪指令。
 */
private val FREECLIP_SOUND_PROFILE = HuaweiProtocolProfile(
    noiseModes = setOf(NoiseMode.ANC, NoiseMode.OFF, NoiseMode.TRANSPARENCY),
    extension = SOUND_EFFECT_EXTENSION,
)
