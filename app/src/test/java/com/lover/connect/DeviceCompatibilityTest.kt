package com.lover.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCompatibilityTest {
    @Test
    fun vivoIdentityUsesPassiveCompatibilityMode() {
        assertEquals(
            DeviceCompatibility.AppInterventionMode.OEM_PASSIVE_COMPAT,
            DeviceCompatibility.appInterventionMode("vivo", "vivo"),
        )
        assertEquals(
            DeviceCompatibility.AppInterventionMode.OEM_PASSIVE_COMPAT,
            DeviceCompatibility.appInterventionMode("VIVO", "iQOO"),
        )
    }

    @Test
    fun oppoIdentityUsesConservativeCallbacksAndActiveInterventions() {
        listOf(
            DeviceCompatibility.policy("OPPO", "OPPO"),
            DeviceCompatibility.policy("oppo", "CPH2607"),
            DeviceCompatibility.policy("unknown", "Oppo"),
        ).forEach { policy ->
            assertEquals(
                DeviceCompatibility.AccessibilityStabilityMode.OEM_CONSERVATIVE,
                policy.accessibilityStabilityMode,
            )
            assertEquals(
                DeviceCompatibility.AppInterventionMode.ACTIVE,
                policy.appInterventionMode,
            )
        }
    }

    @Test
    fun xiaomiAndOtherAndroidDevicesKeepActiveInterventions() {
        assertEquals(
            DeviceCompatibility.AppInterventionMode.ACTIVE,
            DeviceCompatibility.appInterventionMode("Xiaomi", "Redmi"),
        )
        assertEquals(
            DeviceCompatibility.AccessibilityStabilityMode.STANDARD,
            DeviceCompatibility.policy("Xiaomi", "Redmi").accessibilityStabilityMode,
        )
        assertEquals(
            DeviceCompatibility.AppInterventionMode.ACTIVE,
            DeviceCompatibility.appInterventionMode("Google", "google"),
        )
        assertEquals(
            DeviceCompatibility.AppInterventionMode.ACTIVE,
            DeviceCompatibility.appInterventionMode("not-vivo", "generic"),
        )
    }
}
