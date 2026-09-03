package com.lover.connect

object McpServiceLifecyclePolicy {
    const val DEFAULT_ENABLED = false
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    fun handlesRestoreBroadcast(action: String?): Boolean =
        action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED

    fun shouldRequestStart(enabled: Boolean): Boolean = enabled

    /**
     * Versions before 2.4.1-r2 did not persist the MCP run preference. Their
     * service was effectively always-on, so the first upgrade to this policy
     * migrates a missing preference to enabled. A clean install remains opt-in.
     */
    fun shouldMigrateLegacyEnabled(
        action: String?,
        hasStoredPreference: Boolean,
        hasLegacyUseEvidence: Boolean,
    ): Boolean =
        action == ACTION_MY_PACKAGE_REPLACED &&
            !hasStoredPreference &&
            hasLegacyUseEvidence
}
