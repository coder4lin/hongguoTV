// Light fingerprint constants for the fqnovel app endpoints, adapted from
// jipinwa/hongguo (native/core/provider_hongguo_app.go). GPL-3.0-only.
// The original fixed iid/device_id pair from drpys 22261ad was blocked by the
// platform; every install now derives a stable random identity instead.
package com.hongguotv.core

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

internal object VendorConstants {
    const val VIDEO_UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    const val APP_UA = VIDEO_UA
    private val stored = AtomicReference<Map<String, String>?>(null)
    val device: Map<String, String>
        get() = stored.get() ?: generate().also { stored.set(it) }

    // 18-19 digit decimal id in the range the Go client generates; kept for the
    // process lifetime so pagination sessions and signatures stay consistent.
    private fun generate(): Map<String, String> {
        val random = SecureRandom()
        fun id(): String {
            var value = java.math.BigInteger(63, random).add(java.math.BigInteger.ONE.shiftLeft(59))
            return value.toString()
        }
        return linkedMapOf(
            "iid" to id(),
            "device_id" to id(),
            "ac" to "wifi",
            "channel" to "update_64",
            "aid" to "8662",
            "app_name" to "novelread",
            "version_code" to "73532",
            "version_name" to "7.3.5.32",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to "25053RT47C",
            "device_brand" to "Redmi",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "manifest_version_code" to "73532",
            "resolution" to "1280*2772",
            "dpi" to "520",
            "update_version_code" to "73532",
            "host_abi" to "arm64-v8a",
            "dragon_device_type" to "phone",
            "pv_player" to "73532",
            "compliance_status" to "0",
            "need_personal_recommend" to "1",
            "player_so_load" to "1",
            "is_android_pad_screen" to "0"
        )
    }
}
