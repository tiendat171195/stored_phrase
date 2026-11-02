package io.github.tiendat171195.storedphrase.security

/** The vault keys were destroyed by Android (e.g. screen lock removed). Stored data is unrecoverable. */
class VaultKeyInvalidatedException(cause: Throwable? = null) : Exception("Vault keys are no longer usable", cause)

/** A stored file is damaged or has been tampered with (GCM authentication failed or bad format). */
class VaultCorruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The device cannot create StrongBox-backed keys and TEE fallback is not allowed (release builds). */
class NoStrongBoxException(cause: Throwable? = null) : Exception("StrongBox is not available", cause)

/** Keys that require user authentication cannot exist without a secure lock screen. */
class NoLockScreenException : Exception("A secure screen lock is required")
