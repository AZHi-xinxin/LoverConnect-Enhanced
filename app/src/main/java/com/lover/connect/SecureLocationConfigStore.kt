package com.lover.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores raw zone coordinates encrypted with a non-exportable Android Keystore key. */
class SecureLocationConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LocationSafetyConfig {
        val ivText = prefs.getString(KEY_IV, null) ?: return LocationSafetyConfig()
        val cipherText = prefs.getString(KEY_CIPHER_TEXT, null) ?: return LocationSafetyConfig()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(ivText, Base64.NO_WRAP))
            )
            decodeConfig(String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (error: Exception) {
            throw LocationConfigUnavailableException(
                "Encrypted location settings could not be opened; clear them explicitly before reconfiguring",
                error
            )
        }
    }

    fun save(config: LocationSafetyConfig) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(encodeConfig(config).toByteArray(Charsets.UTF_8))
        val written = prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHER_TEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(written) { "Encrypted location settings were not persisted" }
    }

    fun upsertZone(zone: SafetyZone): LocationSafetyConfig {
        val current = load()
        val zones = current.zones.filterNot { it.id == zone.id } + zone
        val updated = current.copy(zones = zones.sortedBy { it.id })
        save(updated)
        return updated
    }

    fun saveSecondReminderMeters(meters: Int): LocationSafetyConfig {
        val updated = load().copy(secondReminderMeters = meters)
        save(updated)
        return updated
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "Encrypted location settings were not cleared" }
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encodeConfig(config: LocationSafetyConfig): String = JSONObject().apply {
        put("version", 1)
        put("second_reminder_meters", config.secondReminderMeters)
        put("stable_duration_ms", config.stableDurationMs)
        put("valid_accuracy_meters", config.validAccuracyMeters.toDouble())
        put("minimum_sample_spacing_ms", config.minimumSampleSpacingMs)
        put("second_reminder_delay_ms", config.secondReminderDelayMs)
        put("zones", JSONArray().apply {
            config.zones.forEach { zone ->
                put(JSONObject().apply {
                    put("id", zone.id)
                    put("label", zone.label)
                    put("latitude", zone.latitude)
                    put("longitude", zone.longitude)
                    put("radius_meters", zone.radiusMeters)
                })
            }
        })
    }.toString()

    private fun decodeConfig(raw: String): LocationSafetyConfig {
        val json = JSONObject(raw)
        require(json.optInt("version", 0) == 1) { "Unsupported location config version" }
        val zoneArray = json.optJSONArray("zones") ?: JSONArray()
        val zones = (0 until zoneArray.length()).map { index ->
            val zone = zoneArray.getJSONObject(index)
            SafetyZone(
                id = zone.getString("id"),
                label = zone.getString("label"),
                latitude = zone.getDouble("latitude"),
                longitude = zone.getDouble("longitude"),
                radiusMeters = zone.optInt("radius_meters", 500)
            )
        }
        return LocationSafetyConfig(
            zones = zones,
            secondReminderMeters = json.optInt("second_reminder_meters", 5_000),
            stableDurationMs = json.optLong("stable_duration_ms", 180_000L),
            validAccuracyMeters = json.optDouble("valid_accuracy_meters", 100.0).toFloat(),
            minimumSampleSpacingMs = json.optLong("minimum_sample_spacing_ms", 45_000L),
            secondReminderDelayMs = json.optLong("second_reminder_delay_ms", 900_000L)
        )
    }

    companion object {
        private const val PREFS_NAME = "lc_location_secure"
        private const val KEY_IV = "config_iv"
        private const val KEY_CIPHER_TEXT = "config_ciphertext"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "loverconnect_location_config_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

class LocationConfigUnavailableException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)
