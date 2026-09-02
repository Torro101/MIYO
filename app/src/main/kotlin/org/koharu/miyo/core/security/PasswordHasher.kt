package org.koharu.miyo.core.security

import android.util.Base64
import org.koitharu.kotatsu.parsers.util.md5
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {

	private const val PREFIX = "pbkdf2-sha1"
	private const val DELIMITER = '$'
	private const val ITERATIONS = 120_000
	private const val KEY_LENGTH_BITS = 256
	private const val SALT_BYTES = 16
	private const val PARTS_COUNT = 4

	fun create(password: String): String {
		val salt = ByteArray(SALT_BYTES)
		SecureRandom().nextBytes(salt)
		val hash = derive(password, salt, ITERATIONS)
		return listOf(
			PREFIX,
			ITERATIONS.toString(),
			salt.encodeBase64(),
			hash.encodeBase64(),
		).joinToString(DELIMITER.toString())
	}

	fun verify(password: String, storedHash: String?): Boolean {
		if (storedHash.isNullOrBlank()) {
			return false
		}
		if (!storedHash.startsWith("${PREFIX}${DELIMITER}")) {
			return password.md5() == storedHash
		}
		val parts = storedHash.split('$', limit = PARTS_COUNT)
		if (parts.size != PARTS_COUNT || parts[0] != PREFIX) {
			return false
		}
		val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
		val salt = parts[2].decodeBase64OrNull() ?: return false
		val expected = parts[3].decodeBase64OrNull() ?: return false
		val actual = derive(password, salt, iterations)
		return MessageDigest.isEqual(actual, expected)
	}

	fun shouldUpgrade(storedHash: String?): Boolean {
		return !storedHash.isNullOrBlank() && !storedHash.startsWith("${PREFIX}${DELIMITER}")
	}

	private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
		val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
		return try {
			SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

	private fun String.decodeBase64OrNull(): ByteArray? = runCatching {
		Base64.decode(this, Base64.NO_WRAP)
	}.getOrNull()
}
