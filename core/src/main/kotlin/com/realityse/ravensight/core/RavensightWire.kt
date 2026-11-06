package com.realityse.ravensight.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The pure pieces of the delivery contract (docs/sdk/DELIVERY_CONTRACT.md):
 * the queue file name, request signing and gzip. Checked against the shared
 * vectors in the tests.
 */
object RavensightWire {
    /** A body this size or larger (before compression) is sent gzipped. */
    const val GZIP_THRESHOLD: Int = 1024

    /** 64-bit FNV-1a of the UTF-8 bytes, as 16 lower case hex characters. */
    fun fnv1a64Hex(text: String): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash).padStart(16, '0')
    }

    /** queue-<16 hex>.json for this ingest key. */
    fun queueFileName(ingestKey: String): String = "queue-${fnv1a64Hex(ingestKey)}.json"

    /** Lower case hex HMAC-SHA256 keyed with the UTF-8 bytes of [secret] (used as text). */
    fun hmacHex(secret: String, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }

    /** The X-Ravensight-Signature value: "v1=" + HMAC of "v1:<timestamp>:<body>". */
    fun signatureHeader(secret: String, timestampSec: Long, body: String): String =
        "v1=" + hmacHex(secret, "v1:$timestampSec:$body".toByteArray(Charsets.UTF_8))

    /** The beacon form's signature over the ordinary body text (contract 7.4). */
    fun beaconSignature(secret: String, timestampSec: Long, body: String): String =
        hmacHex(secret, "v1:$timestampSec:$body".toByteArray(Charsets.UTF_8))

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
