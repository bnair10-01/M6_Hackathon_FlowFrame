package com.stevencastro.wornhub

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * BLE protocol between the iPhone (central) and the glasses (peripheral).
 * Mirrored in ios/WornHub/WornHub/HubProtocol.swift; keep them in sync.
 *
 * Messages are UTF-8 JSON, split into chunks: seq u16, total u16 (little
 * endian), then payload. The phone writes commands to CMD; the glasses send
 * events (status, app list, command results) as notifications on EVT.
 */
object HubProtocol {
    val SERVICE: UUID = UUID.fromString("7f1e0000-5e2a-4c8e-9b1a-3c6f8e2d4a10")
    /** Phone → glasses, write with response: chunked JSON command. */
    val CMD: UUID = UUID.fromString("7f1e0002-5e2a-4c8e-9b1a-3c6f8e2d4a10")
    /** Glasses → phone, notify: chunked JSON event. */
    val EVT: UUID = UUID.fromString("7f1e0003-5e2a-4c8e-9b1a-3c6f8e2d4a10")
    /** Encrypted read: the HTTP access token (reading it makes iOS pair). */
    val TOKEN: UUID = UUID.fromString("7f1e0004-5e2a-4c8e-9b1a-3c6f8e2d4a10")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val HTTP_PORT = 47900
    const val BONJOUR_TYPE = "_wornhub._tcp"
    const val HEADER = 4

    /** Splits a message into chunks of at most [maxChunk] bytes. */
    fun split(message: ByteArray, maxChunk: Int): List<ByteArray> {
        val payload = (maxChunk - HEADER).coerceAtLeast(1)
        val count = maxOf(1, (message.size + payload - 1) / payload)
        return (0 until count).map { seq ->
            val from = seq * payload
            val to = minOf(message.size, from + payload)
            ByteBuffer.allocate(HEADER + to - from).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(seq.toShort()).putShort(count.toShort())
                .put(message, from, to - from).array()
        }
    }
}

/**
 * Reassembles chunked messages. Chunk 0 starts a new message; anything out
 * of sequence resets, and the sender's next full message still gets through.
 */
class ChunkAssembler {
    private val buffer = ByteArrayOutputStream()
    private var expected = 0
    private var total = 0

    /** Returns the complete message after its last chunk, else null. */
    fun accept(chunk: ByteArray): ByteArray? {
        if (chunk.size < HubProtocol.HEADER) return null
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
        buffer.write(chunk, HubProtocol.HEADER, chunk.size - HubProtocol.HEADER)
        expected++
        if (expected < total) return null
        expected = 0
        return buffer.toByteArray().also { buffer.reset() }
    }
}
