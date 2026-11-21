package com.stevencastro.wornhub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubTest {
    @Test
    fun chunksRoundTrip() {
        val message = """{"type":"apps","apps":[${(1..40).joinToString { """{"pkg":"com.example.app$it","label":"App $it"}""" }}]}""".toByteArray()
        val chunks = HubProtocol.split(message, 20)
        assertTrue(chunks.all { it.size <= 20 })
        val a = ChunkAssembler()
        var out: ByteArray? = null
        chunks.forEachIndexed { i, c ->
            val r = a.accept(c)
            if (i < chunks.size - 1) assertNull(r) else out = r
        }
        assertEquals(String(message), String(assertNotNull(out)))
    }

    @Test
    fun emptyAndSingleChunk() {
        val a = ChunkAssembler()
        assertEquals("{}", String(assertNotNull(a.accept(HubProtocol.split("{}".toByteArray(), 180).single()))))
    }

    @Test
    fun outOfOrderResetsThenRecovers() {
        val chunks = HubProtocol.split(ByteArray(100) { it.toByte() }, 24)
        val a = ChunkAssembler()
        a.accept(chunks[0])
        assertNull(a.accept(chunks[2]))
        var out: ByteArray? = null
        chunks.forEach { out = a.accept(it) ?: out }
        assertEquals(100, assertNotNull(out).size)
    }

    @Test
    fun parsesRequest() {
        val r = assertNotNull(HttpRequest.parse(listOf(
            "GET /media/v12?t=abc%20d&x=1 HTTP/1.1", "Host: glasses", "Range: bytes=0-99")))
        assertEquals("GET", r.method)
        assertEquals("/media/v12", r.path)
        assertEquals("abc d", r.token)
        assertEquals("bytes=0-99", r.headers["range"])
    }

    @Test
    fun bearerTokenWins() {
        val r = assertNotNull(HttpRequest.parse(listOf("GET /live?t=q HTTP/1.1", "Authorization: Bearer secret")))
        assertEquals("secret", r.token)
    }

    @Test
    fun rejectsGarbageRequest() {
        assertNull(HttpRequest.parse(emptyList()))
        assertNull(HttpRequest.parse(listOf("GARBAGE")))
    }

    @Test
    fun ranges() {
        assertNull(ByteRange.parse(null, 1000))
        assertEquals(ByteRange(0, 99), ByteRange.parse("bytes=0-99", 1000))
        assertEquals(ByteRange(500, 999), ByteRange.parse("bytes=500-", 1000))
        assertEquals(ByteRange(900, 999), ByteRange.parse("bytes=-100", 1000))
        assertEquals(ByteRange(0, 999), ByteRange.parse("bytes=0-5000", 1000))
        assertEquals(2, ByteRange.parse("bytes=0-1", 1000)!!.length)
        assertNull(ByteRange.parse("bytes=0-1,5-6", 1000)) // multi-range: send whole file
        assertFailsWith<IllegalArgumentException> { ByteRange.parse("bytes=2000-", 1000) }
    }

    @Test
    fun tokens() {
        assertTrue(tokenMatches("abc", "abc"))
        assertFalse(tokenMatches("abd", "abc"))
        assertFalse(tokenMatches(null, "abc"))
    }

    @Test
    fun batteryCapacityEstimate() {
        // 340 mAh battery at 80% reporting 238,000 µAh → 297.5 mAh full → 88%.
        val full = BatteryHealth.fullCapacityMah(238_000, 80)
        assertEquals(297.5, full!!, 0.01)
        assertEquals(88, BatteryHealth.percentOfDesign(full, 340.0))
        assertNull(BatteryHealth.fullCapacityMah(50_000, 10), "too low a level to trust")
        assertNull(BatteryHealth.percentOfDesign(full, null))
        assertEquals(100, BatteryHealth.percentOfDesign(400.0, 340.0), "capped")
        // Generic power profile (1900) vs a measured 239 mAh cell: unknown, not "13%".
        assertNull(BatteryHealth.percentOfDesign(239.0, 1900.0))
        assertEquals("Overheating", BatteryHealth.healthName(3))
    }
}
