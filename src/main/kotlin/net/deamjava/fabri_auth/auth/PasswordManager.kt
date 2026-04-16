// src/main/kotlin/net/deamjava/fabri_auth/auth/PasswordManager.kt
package net.deamjava.fabri_auth.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import net.deamjava.fabri_auth.config.ConfigLoader

object PasswordManager {

    /**
     * Hash a password. In debug mode with raw storage enabled, returns the
     * password prefixed with "RAW:" so it can be detected on verify.
     * In production, always uses BCrypt.
     */
    fun hash(password: String): String {
        return if (ConfigLoader.config.debugRawPasswordStorage) {
            // DEBUG ONLY - never log or print the password
            "RAW:$password"
        } else {
            BCrypt.withDefaults().hashToString(
                ConfigLoader.config.bcryptLogRounds,
                password.toCharArray()
            )
        }
    }

    /**
     * Verify a plaintext password against a stored hash.
     * Handles both BCrypt and (debug) raw storage transparently.
     */
    fun verify(password: String, storedHash: String): Boolean {
        return if (storedHash.startsWith("RAW:")) {
            // Debug raw comparison — still avoid logging password
            storedHash.removePrefix("RAW:") == password
        } else {
            BCrypt.verifyer()
                .verify(password.toCharArray(), storedHash)
                .verified
        }
    }

    /**
     * Validate password constraints (length, etc.).
     */
    fun isValidPassword(password: String): Boolean {
        return password.length in 4..64
    }
}