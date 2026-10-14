package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SignerLightTest {
    @Test fun matchesPinnedGoImplementationVector() {
        // Reference computed with the Go provider_hongguo_sign.go algorithm over the exact
        // query string the implementation signs (keys sorted, '_' before letters):
        // "_rticket=1728000000000&a=1&b=2", body "body".
        val ts=1728000000L
        val signer=SignerLight({ ts*1000 })
        val request=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/x",linkedMapOf("a" to "1","b" to "2"),"body".toByteArray())
        assertEquals("8404401c0000ac8d64ba8612705c7022103fad22c919494746c1",request.headers["x-gorgon"])
        assertEquals(ts.toString(),request.headers["x-khronos"])
        assertEquals(md5("body".toByteArray()).joinToString("") { "%02X".format(it) },request.headers["x-ss-stub"])
        assertTrue(request.url.endsWith("?_rticket=${ts*1000}&a=1&b=2"))
    }

    @Test fun queryWithoutBodyOmitsStubHeader() {
        val signer=SignerLight({ 1000L })
        val request=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/x",emptyMap())
        assertNull(request.headers["x-ss-stub"])
        assertNull(request.body)
    }

    @Test fun encodesQueryWithSortedKeysLikeGoUrlValues() {
        assertEquals("a=1&b=2&z=%E4%BF%AE%E4%BB%99",SignerLight.encode(linkedMapOf("z" to "修仙","a" to "1","b" to "2")))
    }

    private fun md5(data: ByteArray)=MessageDigest.getInstance("MD5").digest(data)
}
