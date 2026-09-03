package com.lover.connect

import android.os.Build

/** Runtime-only device routing for vendor-specific accessibility workarounds. */
object DeviceCompatibility {
    enum class AccessibilityStabilityMode {
        STANDARD,
        OEM_CONSERVATIVE,
    }

    enum class AppInterventionMode {
        ACTIVE,
        OEM_PASSIVE_COMPAT,
    }

    data class Policy(
        val accessibilityStabilityMode: AccessibilityStabilityMode,
        val appInterventionMode: AppInterventionMode,
    )

    fun policy(manufacturer: String?, brand: String?): Policy {
        val identities = listOf(manufacturer, brand)
            .map { it.orEmpty().trim().lowercase() }
        val isVivo = identities.any { it == "vivo" }
        val isOppo = identities.any { it == "oppo" }
        return when {
            // Vivo remains on the already proven passive path: active callbacks
            // caused its accessibility grant to be reclaimed during RC5 tests.
            isVivo -> Policy(
                accessibilityStabilityMode = AccessibilityStabilityMode.OEM_CONSERVATIVE,
                appInterventionMode = AppInterventionMode.OEM_PASSIVE_COMPAT,
            )

            // OPPO keeps conservative lifecycle diagnostics, but real-device
            // testing on ColorOS shows the service stays bound. Do not disable
            // app lock merely because the manufacturer is OPPO.
            isOppo -> Policy(
                accessibilityStabilityMode = AccessibilityStabilityMode.OEM_CONSERVATIVE,
                appInterventionMode = AppInterventionMode.ACTIVE,
            )

            else -> Policy(
                accessibilityStabilityMode = AccessibilityStabilityMode.STANDARD,
                appInterventionMode = AppInterventionMode.ACTIVE,
            )
        }
    }

    fun appInterventionMode(manufacturer: String?, brand: String?): AppInterventionMode =
        policy(manufacturer, brand).appInterventionMode

    fun currentPolicy(): Policy = policy(Build.MANUFACTURER, Build.BRAND)

    fun currentAppInterventionMode(): AppInterventionMode =
        currentPolicy().appInterventionMode

    fun activeAppInterventionsSupported(): Boolean =
        currentAppInterventionMode() == AppInterventionMode.ACTIVE
}
