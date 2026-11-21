package com.stevencastro.wornrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunProtocolTest {
    @Test
    fun fixRoundTrip() {
        val f = Fix(37.7749295, -122.4194155, 52.3, 3.21, 271.5, 4.8, 1_700_000_000_000)
        val bytes = RunProtocol.encodeFix(f)
        assertEquals(20, bytes.size)
        val d = assertNotNull(RunProtocol.decodeFix(bytes, 42))
        assertEquals(f.lat, d.lat, 1e-7)
        assertEquals(f.lon, d.lon, 1e-7)
        assertEquals(52.3, d.altM, 0.05)
        assertEquals(3.21, d.speedMps, 0.005)
        assertEquals(271.5, d.courseDeg, 0.005)
        assertEquals(4.8, d.hAccM, 0.05)
        assertEquals(42, d.timeMs)
    }

    @Test
    fun unknownSpeedAndCourse() {
        val f = Fix(1.0, 2.0, 0.0, Double.NaN, Double.NaN, 10.0, 0)
        val d = assertNotNull(RunProtocol.decodeFix(RunProtocol.encodeFix(f), 0))
        assertTrue(d.speedMps.isNaN())
        assertTrue(d.courseDeg.isNaN())
    }

    @Test
    fun routeRoundTripThroughChunks() {
        val lats = DoubleArray(300) { 37.0 + it * 1e-4 }
        val lons = DoubleArray(300) { -122.0 - it * 1e-4 }
        val route = Route(lats, lons, listOf(Turn(10, -90, "Left onto Main St"), Turn(200, 45, "Bear right")))
        val body = RunProtocol.encodeRoute(route)
        val assembler = RouteAssembler()
        val chunks = RouteAssembler.split(body, 180)
        var result: ByteArray? = null
        chunks.forEachIndexed { i, c ->
            val r = assembler.accept(c)
            if (i < chunks.size - 1) assertNull(r) else result = r
        }
        val decoded = assertNotNull(RunProtocol.decodeRoute(assertNotNull(result)))
        assertEquals(300, decoded.size)
        assertEquals(lats[150], decoded.lats[150], 1e-7)
        assertEquals("Left onto Main St", decoded.turns[0].label)
        assertEquals(45, decoded.turns[1].angleDeg)
    }

    @Test
    fun outOfOrderChunkResets() {
        val body = ByteArray(1000) { it.toByte() }
        val chunks = RouteAssembler.split(body, 100)
        val a = RouteAssembler()
        a.accept(chunks[0])
        assertNull(a.accept(chunks[2]))
        // A fresh, complete send still works.
        var out: ByteArray? = null
        chunks.forEach { out = a.accept(it) ?: out }
        assertEquals(body.toList(), assertNotNull(out).toList())
    }

    @Test
    fun rejectsGarbage() {
        assertNull(RunProtocol.decodeRoute(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(RunProtocol.decodeFix(ByteArray(5), 0))
    }
}
