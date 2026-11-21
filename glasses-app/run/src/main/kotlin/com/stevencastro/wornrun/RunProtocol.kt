package com.stevencastro.wornrun

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE protocol between the iPhone (GATT central) and the glasses (GATT
 * peripheral). Mirrored in ios/WornRun/RunProtocol.swift; keep them in sync.
 * All integers are little-endian.
 */
object RunProtocol {
    val SERVICE: UUID = UUID.fromString("7f1d0000-5e2a-4c8e-9b1a-3c6f8e2d4a10")

    /** Phone → glasses, write without response: one location fix (20 bytes). */
    val FIX: UUID = UUID.fromString("7f1d0001-5e2a-4c8e-9b1a-3c6f8e2d4a10")

    /** Phone → glasses, write with response: route, in chunks (see [RouteAssembler]). */
    val ROUTE: UUID = UUID.fromString("7f1d0002-5e2a-4c8e-9b1a-3c6f8e2d4a10")

    /** Phone → glasses, write with response: UTF-8 JSON command. */
    val CONTROL: UUID = UUID.fromString("7f1d0003-5e2a-4c8e-9b1a-3c6f8e2d4a10")

    /** Glasses → phone, notify: UTF-8 JSON status, about once a second. */
    val STATUS: UUID = UUID.fromString("7f1d0004-5e2a-4c8e-9b1a-3c6f8e2d4a10")

    /** Standard Client Characteristic Configuration descriptor (enables notify). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val FIX_BYTES = 20
    private const val ROUTE_MAGIC = 0x5257 // "WR"
    private const val ROUTE_VERSION = 1

    /**
     * FIX layout: lat i32 (1e-7 deg), lon i32 (1e-7 deg), alt i16 (dm),
     * speed u16 (cm/s, 0xFFFF = unknown), course u16 (0.01 deg, 0xFFFF =
     * unknown), hAcc u16 (dm), time u32 (unix seconds).
     */
    fun decodeFix(bytes: ByteArray, receivedAtMs: Long): Fix? {
        if (bytes.size < FIX_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val lat = b.int / 1e7
        val lon = b.int / 1e7
        val alt = b.short / 10.0
        val speed = b.short.toInt() and 0xFFFF
        val course = b.short.toInt() and 0xFFFF
        val hAcc = b.short.toInt() and 0xFFFF
        b.int // phone timestamp; we use receive time so clocks needn't agree
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Fix(
            lat = lat,
            lon = lon,
            altM = alt,
            speedMps = if (speed == 0xFFFF) Double.NaN else speed / 100.0,
            courseDeg = if (course == 0xFFFF) Double.NaN else course / 100.0,
            hAccM = hAcc / 10.0,
            timeMs = receivedAtMs,
        )
    }

    fun encodeFix(fix: Fix): ByteArray {
        val b = ByteBuffer.allocate(FIX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(Math.round(fix.lat * 1e7).toInt())
        b.putInt(Math.round(fix.lon * 1e7).toInt())
        b.putShort(Math.round(fix.altM * 10).coerceIn(-32768, 32767).toInt().toShort())
        b.putShort(if (fix.speedMps.isNaN()) -1 else Math.round(fix.speedMps * 100).coerceIn(0, 65534).toInt().toShort())
        b.putShort(if (fix.courseDeg.isNaN()) -1 else Math.round(fix.courseDeg * 100).coerceIn(0, 35999).toInt().toShort())
        b.putShort(Math.round(fix.hAccM * 10).coerceIn(0, 65535).toInt().toShort())
        b.putInt((fix.timeMs / 1000).toInt())
        return b.array()
    }

    /**
     * Route body (before chunking): magic u16, version u8, pointCount u16,
     * turnCount u16, then pointCount × (lat i32, lon i32), then turnCount ×
     * (pointIndex u16, angle i16 degrees, + = right, labelLen u8, label UTF-8).
     */
    fun decodeRoute(body: ByteArray): Route? {
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if (body.size < 7) return null
        if ((b.short.toInt() and 0xFFFF) != ROUTE_MAGIC) return null
        if (b.get().toInt() != ROUTE_VERSION) return null
        val pointCount = b.short.toInt() and 0xFFFF
        val turnCount = b.short.toInt() and 0xFFFF
        if (pointCount < 2 || b.remaining() < pointCount * 8) return null
        val lats = DoubleArray(pointCount)
        val lons = DoubleArray(pointCount)
        for (i in 0 until pointCount) {
            lats[i] = b.int / 1e7
            lons[i] = b.int / 1e7
        }
        val turns = ArrayList<Turn>(turnCount)
        repeat(turnCount) {
            if (b.remaining() < 5) return null
            val index = b.short.toInt() and 0xFFFF
            val angle = b.short.toInt()
            val len = b.get().toInt() and 0xFF
            if (b.remaining() < len) return null
            val label = ByteArray(len).also { b.get(it) }.toString(Charsets.UTF_8)
            if (index < pointCount) turns.add(Turn(index, angle, label))
        }
        return Route(lats, lons, turns)
    }

    fun encodeRoute(route: Route): ByteArray {
        val labels = route.turns.map { it.label.toByteArray(Charsets.UTF_8).take(255).toByteArray() }
        val size = 7 + route.size * 8 + route.turns.indices.sumOf { 5 + labels[it].size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(ROUTE_MAGIC.toShort()).put(ROUTE_VERSION.toByte())
        b.putShort(route.size.toShort()).putShort(route.turns.size.toShort())
        for (i in 0 until route.size) {
            b.putInt(Math.round(route.lats[i] * 1e7).toInt())
            b.putInt(Math.round(route.lons[i] * 1e7).toInt())
        }
        route.turns.forEachIndexed { i, t ->
            b.putShort(t.pointIndex.toShort()).putShort(t.angleDeg.toShort())
            b.put(labels[i].size.toByte()).put(labels[i])
        }
        return b.array()
    }
}

/**
 * Reassembles a route sent as chunks: each chunk is seq u16, total u16, then
 * payload. Chunk 0 starts a new route; a gap or a repeat resets.
 */
class RouteAssembler {
    private val buffer = java.io.ByteArrayOutputStream()
    private var expected = 0
    private var total = 0

    /** Returns the finished body after the last chunk, else null. */
    fun accept(chunk: ByteArray): ByteArray? {
        if (chunk.size < 4) return null
        val b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val seq = b.short.toInt() and 0xFFFF
        val count = b.short.toInt() and 0xFFFF
        if (seq == 0) {
            buffer.reset()
            expected = 0
            total = count
        }
        if (seq != expected || count != total || total == 0) {
            buffer.reset()
            expected = 0
            return null
        }
        buffer.write(chunk, 4, chunk.size - 4)
        expected++
        if (expected < total) return null
        expected = 0
        return buffer.toByteArray().also { buffer.reset() }
    }

    companion object {
        /** Splits a route body into chunks that fit [maxChunk] bytes. */
        fun split(body: ByteArray, maxChunk: Int): List<ByteArray> {
            val payload = maxChunk - 4
            val count = (body.size + payload - 1) / payload
            return (0 until count).map { seq ->
                val from = seq * payload
                val to = minOf(body.size, from + payload)
                ByteBuffer.allocate(4 + to - from).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(seq.toShort()).putShort(count.toShort())
                    .put(body, from, to - from).array()
            }
        }
    }
}
