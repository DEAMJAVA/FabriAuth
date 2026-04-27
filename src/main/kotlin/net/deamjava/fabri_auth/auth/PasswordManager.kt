package net.deamjava.fabri_auth.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import net.deamjava.fabri_auth.config.ConfigLoader

object PasswordManager {

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

    fun isValidPassword(password: String): Boolean {
        return password.length in 4..64
    }
}