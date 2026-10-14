// Light-weight signature scheme for the fqnovel app endpoints, adapted from
// jipinwa/hongguo (native/core/provider_hongguo_sign.go). GPL-3.0-only.
// Replaces the heavy drpys gorgon/helios/medusa stack that relied on fixed
// device fingerprints (now blocked by the platform).
package com.hongguotv.core

import java.security.MessageDigest

internal class SignerLight(private val clock: () -> Long = System::currentTimeMillis) {
    data class Request(val url: String, val headers: Map<String, String>, val body: ByteArray?)

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun sign(base: String, values: Map<String, String>, body: ByteArray? = null): Request {
        val now = clock()
        val ts = (now / 1000).toInt()
        val query = encode(values + mapOf("_rticket" to now.toString()))
        val url = "$base?$query"
        val payload = ByteArray(20)
        md5(query.toByteArray(Charsets.UTF_8)).copyInto(payload, 0, 0, 4)
        val stub: String?
        if (body != null) {
            val bodyHash = md5(body)
            bodyHash.copyInto(payload, 4, 0, 4)
            stub = bodyHash.joinToString("") { "%02X".format(it) }
        } else {
            stub = null
        }
        payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28
        payload[16] = (ts ushr 24).toByte(); payload[17] = (ts ushr 16).toByte()
        payload[18] = (ts ushr 8).toByte(); payload[19] = ts.toByte()
        val key = byteArrayOf(
            0x44, 0xb9.toByte(), 0xb9.toByte(), 0xd9.toByte(), 0xa4.toByte(), 0xae.toByte(), 0xf9.toByte(), 0xfc.toByte(),
            0xa4.toByte(), 0x93.toByte(), 0xaa.toByte(), 0x75, 0x7c, 0xa3.toByte(), 0xc2.toByte(), 0xc4.toByte(),
            0xa4.toByte(), 0x96.toByte(), 0x93.toByte(), 0x8f.toByte()
        )
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor key[i].toInt()).toByte()
        for (i in payload.indices) {
            val u = payload[i].toInt() and 0xff
            val rotated = ((u shl 4) or (u ushr 4)) and 0xff
            val mixed = rotated xor (payload[(i + 1) % payload.size].toInt() and 0xff)
            payload[i] = (((Integer.reverse(mixed and 0xff) ushr 24) xor 0xff xor payload.size) and 0xff).toByte()
        }
        val signature = ByteArray(26)
        signature[0] = 0x84.toByte(); signature[1] = 0x04; signature[2] = 0x40; signature[3] = 0x1c
        payload.copyInto(signature, 6)
        val gorgon = signature.joinToString("") { b -> "%02x".format(b) }
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2",
            "x-khronos" to ts.toString(),
            "x-gorgon" to gorgon,
            "x-ss-req-ticket" to now.toString(),
        )
        if (stub != null) {
            headers["Content-Type"] = "application/json; charset=utf-8"
            headers["x-ss-stub"] = stub
        }
        return Request(url, headers, body)
    }

    companion object {
        // Mirror Go's url.Values.Encode(): keys sorted, standard form encoding.
        fun encode(values: Map<String, String>) = values.entries.sortedBy { it.key }.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
    }
}
