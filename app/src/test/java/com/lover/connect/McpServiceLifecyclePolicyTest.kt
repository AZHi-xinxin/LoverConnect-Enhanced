package com.lover.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServiceLifecyclePolicyTest {
    @Test
    fun packageReplacementAndBootAreRestoreTriggers() {
        assertTrue(
            McpServiceLifecyclePolicy.handlesRestoreBroadcast(
                McpServiceLifecyclePolicy.ACTION_BOOT_COMPLETED,
            ),
        )
        assertTrue(
            McpServiceLifecyclePolicy.handlesRestoreBroadcast(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
            ),
        )
    }

    @Test
    fun unrelatedOrMissingBroadcastIsIgnored() {
        assertFalse(McpServiceLifecyclePolicy.handlesRestoreBroadcast(null))
        assertFalse(McpServiceLifecyclePolicy.handlesRestoreBroadcast("android.intent.action.TIME_SET"))
    }

    @Test
    fun enabledStoppedServiceRequestsStart() {
        assertTrue(McpServiceLifecyclePolicy.shouldRequestStart(enabled = true))
    }

    @Test
    fun disabledServiceDoesNotRequestStart() {
        assertFalse(McpServiceLifecyclePolicy.shouldRequestStart(enabled = false))
    }

    @Test
    fun enabledServiceAlwaysReceivesAnIdempotentStartRequest() {
        assertTrue(McpServiceLifecyclePolicy.shouldRequestStart(enabled = true))
    }

    @Test
    fun onlyFirstLegacyPackageReplacementMigratesToEnabled() {
        assertTrue(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                hasStoredPreference = false,
                hasLegacyUseEvidence = true,
            ),
        )
        assertFalse(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                hasStoredPreference = true,
                hasLegacyUseEvidence = true,
            ),
        )
        assertFalse(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_BOOT_COMPLETED,
                hasStoredPreference = false,
                hasLegacyUseEvidence = true,
            ),
        )
    }

    @Test
    fun neverOpenedCleanInstallIsNotMigratedOnItsFirstReplacement() {
        assertFalse(
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                McpServiceLifecyclePolicy.ACTION_MY_PACKAGE_REPLACED,
                hasStoredPreference = false,
                hasLegacyUseEvidence = false,
            ),
        )
    }

    @Test
    fun cleanInstallDefaultsToStoppedUntilTheUserStartsIt() {
        assertFalse(McpServiceLifecyclePolicy.DEFAULT_ENABLED)
    }
}
