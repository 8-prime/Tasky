package dev.tasky.android.sync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object Uuids {
    /** Namespace for contribution IDs derived from plan entries (docs/API.md). */
    val CONTRIBUTION_NAMESPACE: UUID = UUID.fromString("6d1f3c2a-9b8e-4f6d-a1c3-5e7b9d2f4a10")

    private val random = SecureRandom()

    /** Time-ordered random UUID (RFC 9562 version 7). */
    fun v7(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (8 * (5 - i))).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return fromBytes(bytes).toString()
    }

    /** Name-based UUID (RFC 9562 version 5, SHA-1). */
    fun v5(namespace: UUID, name: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        val hash = sha1.digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
        hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
        return fromBytes(hash).toString()
    }

    /** `uuidv5(CONTRIBUTION_NAMESPACE, "<plan_entry_id>:<meal_ingredient_id>")`. */
    fun contributionId(planEntryId: String, mealIngredientId: String): String =
        v5(CONTRIBUTION_NAMESPACE, "${planEntryId.lowercase()}:${mealIngredientId.lowercase()}")

    private fun fromBytes(b: ByteArray): UUID {
        val buf = ByteBuffer.wrap(b)
        return UUID(buf.long, buf.long)
    }
}
