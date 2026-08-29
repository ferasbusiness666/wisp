package com.wisp.app

import android.content.Context
import java.security.SecureRandom

/**
 * The device's permanent 8-character code. Generated once on first launch and
 * kept in SharedPreferences forever — it is this device's only identity.
 */
object DeviceIdentity {

    /** Crockford-ish alphabet: no O/0/I/1, so a code can be read out loud. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8
    private const val KEY = "device_code"

    @Volatile
    private var cached: String? = null

    fun myCode(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = Prefs.of(context)
            val existing = prefs.getString(KEY, null)
            if (existing != null && isValid(existing)) {
                cached = existing
                return existing
            }
            val generated = generate()
            prefs.edit().putString(KEY, generated).apply()
            cached = generated
            return generated
        }
    }

    fun generate(): String {
        val random = SecureRandom()
        return buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    /** Codes are compared upper-cased and stripped of separators people type. */
    fun normalize(raw: String): String =
        raw.trim().uppercase().filter { it in ALPHABET }

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET }
}
