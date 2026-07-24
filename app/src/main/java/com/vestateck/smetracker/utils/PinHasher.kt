package com.vestateck.smetracker.utils

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN hashing for the offline-login credential store (see LocalCredential /
 * SessionManager). Uses PBKDF2WithHmacSHA1 - built into the Android
 * platform, so no extra native dependency (Argon2/bcrypt) is needed.
 * 120,000 iterations comfortably clears current guidance for PBKDF2-SHA1
 * and costs roughly 50-100ms on a typical device - fine for a once-per-app-
 * open check, but expensive enough to meaningfully slow down brute-forcing
 * a short numeric PIN if the local database were ever pulled off a rooted
 * device.
 */
object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"

    fun generateSalt(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun hash(pin: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val hashBytes = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }

    /** Constant-time-ish comparison so a mismatched PIN can't be timed character-by-character. */
    fun verify(pin: String, saltBase64: String, expectedHash: String): Boolean {
        val actual = hash(pin, saltBase64)
        if (actual.length != expectedHash.length) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].code xor expectedHash[i].code)
        return diff == 0
    }
}