package dev.hyperears.presentation

import dev.hyperears.integration.BatteryReading
import dev.hyperears.integration.BatterySource
import dev.hyperears.integration.EarbudState
import dev.hyperears.integration.HeadsetArtwork
import dev.hyperears.integration.HeadsetFormFactor
import dev.hyperears.settings.HeadsetConnectionPresentation

/** Physical connection provenance is separate from a replaceable protocol session. */
data class HeadsetConnection(
    val id: String,
    val connectedAtElapsedMs: Long,
)

enum class NativeAdmission { UNKNOWN, HYPEREARS, SYSTEM }

data class NativePresentationMetadata(
    val connection: HeadsetConnection,
    val admission: NativeAdmission = NativeAdmission.UNKNOWN,
    val replay: Boolean = false,
)

/** A read-only projection. Aggregate values copied into MiLink slots are not component telemetry. */
data class NativeHeadsetProjection(
    val address: String,
    val name: String,
    val form: HeadsetFormFactor,
    val artwork: HeadsetArtwork,
    val singleBattery: Boolean,
    val left: BatteryReading,
    val right: BatteryReading,
    val chargingCase: BatteryReading,
) {
    val hasBattery: Boolean get() = left.available || right.available || chargingCase.available

    val showCaseInPopup: Boolean
        get() = form == HeadsetFormFactor.TWS && !singleBattery && chargingCase.available

    /** 左右耳电量相同：宿主与状态栏都会当成一个电量，弹窗里只保留一行。 */
    val mergedEars: Boolean
        get() = !singleBattery && left.available && left.percent != null && left.percent == right.percent

    // The native connected island needs ear/headphone telemetry. Its single-battery template
    // becomes an invalid icon-only model before a reading exists; a case reading cannot fill it.
    val canShowConnectionHint: Boolean get() = left.available || right.available

    /** The native one-value island may use either its headphone or generic earbud artwork. */
    val singleBatteryHintArtwork: String
        get() = if (form == HeadsetFormFactor.TWS) "earphone" else "single_battery_head"

    companion object {
        private data class Known(val left: BatteryReading, val right: BatteryReading, val case: BatteryReading)

        private val known = mutableMapOf<String, Known>()

        /** 记住每个地址最后一次已知的电量，缺失时沿用，避免布局抖动。 */
        private fun rememberBattery(
            address: String,
            left: BatteryReading,
            right: BatteryReading,
            case: BatteryReading,
        ): Pair<BatteryReading, BatteryReading> {
            val previous = known[address]
            val bestLeft = if (left.available) left else right.takeIf { it.available } ?: previous?.left ?: left
            val bestRight = if (right.available) right else left.takeIf { it.available } ?: previous?.right ?: right
            val bestCase = if (case.available) case else previous?.case ?: case
            known[address] = Known(bestLeft, bestRight, bestCase)
            val mergedPercent = listOfNotNull(
                bestLeft.percent?.takeIf { bestLeft.available },
                bestRight.percent?.takeIf { bestRight.available },
            ).maxOrNull()
            val merged = if (mergedPercent != null) {
                BatteryReading(mergedPercent, bestLeft.charging || bestRight.charging)
            } else {
                BatteryReading(null, false)
            }
            return merged to bestCase
        }

        fun from(state: EarbudState): NativeHeadsetProjection? {
            val adapter = state.adapter ?: return null
            val address = state.address ?: return null
            if (!state.sessionActive) return null
            val battery = state.battery
            // 本地私有构建：组件电量可用时优先展开展示（左右耳 + 充电盒），
            // 而不是合并成单电量；仅当没有任何组件数据时才回退成单值。
            val single = adapter.formFactor == HeadsetFormFactor.HEADPHONES ||
                (!battery.left.available && !battery.right.available &&
                    adapter.batterySource == BatterySource.SYSTEM_AGGREGATE)
            // 本地私有构建：把左右耳合并成一个电量（取较高值），并记住上次已知值，
            // 避免盒电量暂时缺失时整个布局在 2 行/3 行之间来回跳。
            val merged = rememberBattery(
                address,
                left = battery.left,
                right = battery.right,
                case = battery.case,
            )
            val unknown = BatteryReading(null, false)
            return NativeHeadsetProjection(
                address = address,
                name = state.deviceName?.takeIf(String::isNotBlank) ?: adapter.displayName,
                form = adapter.formFactor,
                artwork = adapter.artwork,
                singleBattery = single,
                left = if (single) battery.overall else merged.first,
                // 左右耳报同一个值：状态栏（岛）只有在两个值相同时才会合并成一个图标 + 一个百分比。
                right = if (single) unknown else merged.first,
                chargingCase = if (single) unknown else merged.second,
            )
        }
    }
}

internal enum class NativePresentationTarget { POPUP, ISLAND }

/** Unknown native scene information cannot authorize an automatic popup. */
internal fun resolveNativePresentationTarget(
    selection: HeadsetConnectionPresentation,
    onHomeOrCoverScreen: Boolean?,
): NativePresentationTarget? = when (selection) {
    HeadsetConnectionPresentation.SMART -> when (onHomeOrCoverScreen) {
        true -> NativePresentationTarget.POPUP
        false -> NativePresentationTarget.ISLAND
        null -> null
    }
    HeadsetConnectionPresentation.BATTERY_POPUP -> NativePresentationTarget.POPUP
    HeadsetConnectionPresentation.SYSTEM_HINT -> NativePresentationTarget.ISLAND
    HeadsetConnectionPresentation.OFF -> null
}

/** No timers or IO; failed eligibility/display checks do not consume a connection. */
internal class ConnectionPresentationGate(private val freshnessMs: Long = 10_000) {
    private val consumed = mutableMapOf<String, Long>()
    private val pending = mutableMapOf<String, Long>()

    fun tryPresent(
        metadata: NativePresentationMetadata,
        now: Long,
        onDecision: (String) -> Unit = {},
        submit: () -> Boolean,
    ): Boolean {
        fun skip(reason: String): Boolean { onDecision(reason); return false }
        consumed.entries.removeAll { now - it.value > freshnessMs }
        pending.entries.removeAll { now - it.value > freshnessMs }
        if (metadata.replay) {
            // A startup replay cannot itself open a window. It must not cancel a live connection
            // already waiting for native admission, plugin readiness or battery telemetry either.
            if (metadata.connection.id !in pending) {
                consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
            }
            return skip("replay")
        }
        if (now - metadata.connection.connectedAtElapsedMs !in 0..freshnessMs) return skip("expired")
        if (metadata.connection.id in consumed) return skip("consumed")
        pending[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
        if (metadata.admission != NativeAdmission.HYPEREARS) return skip("admission-${metadata.admission}")
        if (!submit()) return skip("not-submitted")
        pending.remove(metadata.connection.id)
        consumed[metadata.connection.id] = metadata.connection.connectedAtElapsedMs
        onDecision("submitted")
        return true
    }

    fun retire(id: String) { consumed.remove(id); pending.remove(id) }
    fun clear() { consumed.clear(); pending.clear() }
}
