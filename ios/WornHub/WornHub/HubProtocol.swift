import CoreBluetooth
import Foundation

/// BLE protocol shared with the glasses (glasses-app/hub/.../HubProtocol.kt).
/// Messages are UTF-8 JSON split into chunks: seq u16, total u16
/// (little-endian), then payload.
enum HubProtocol {
    static let service = CBUUID(string: "7F1E0000-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    static let cmd = CBUUID(string: "7F1E0002-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    static let evt = CBUUID(string: "7F1E0003-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    static let token = CBUUID(string: "7F1E0004-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    static let header = 4

    static func split(_ message: Data, maxChunk: Int) -> [Data] {
        let payload = max(1, maxChunk - header)
        let count = max(1, (message.count + payload - 1) / payload)
        return (0..<count).map { seq in
            let from = message.startIndex + seq * payload
            let to = min(message.endIndex, from + payload)
            var c = Data()
            withUnsafeBytes(of: UInt16(seq).littleEndian) { c.append(contentsOf: $0) }
            withUnsafeBytes(of: UInt16(count).littleEndian) { c.append(contentsOf: $0) }
            c.append(message[from..<to])
            return c
        }
    }
}

/// Reassembles chunked messages; anything out of sequence resets.
struct ChunkAssembler {
    private var buffer = Data()
    private var expected = 0
    private var total = 0

    mutating func accept(_ chunk: Data) -> Data? {
        guard chunk.count >= HubProtocol.header else { return nil }
        let bytes = [UInt8](chunk.prefix(4))
        let seq = Int(bytes[0]) | Int(bytes[1]) << 8
        let count = Int(bytes[2]) | Int(bytes[3]) << 8
        if seq == 0 {
            buffer = Data()
            expected = 0
            total = count
        }
        guard seq == expected, count == total, total > 0 else {
            buffer = Data()
            expected = 0
            return nil
        }
        buffer.append(chunk.dropFirst(HubProtocol.header))
        expected += 1
        guard expected == total else { return nil }
        expected = 0
        defer { buffer = Data() }
        return buffer
    }
}

// MARK: Messages from the glasses

struct HubStatus: Decodable, Equatable {
    struct Battery: Decodable, Equatable {
        let level: Int
        let charging: Bool
        let full: Bool
        let source: String
        let health: String
        let tempC: Double
        let voltageMv: Int
        let currentMa: Int
        let fullMah: Int?
        let designMah: Int?
        let capacityPercent: Int?
    }

    struct Storage: Decodable, Equatable {
        let freeBytes: Int64
        let totalBytes: Int64
    }

    struct Wifi: Decodable, Equatable {
        let enabled: Bool
        let connected: Bool
        let ssid: String
        let ip: String
        let port: Int
    }

    let model: String
    let android: String
    let battery: Battery
    let storage: Storage
    let wifi: Wifi
    let foreground: String
    let phones: Int
    let liveViewers: Int
}

struct GlassesApp: Decodable, Hashable, Identifiable {
    let package: String
    let label: String
    var id: String { package }
}

enum HubEvent {
    case status(HubStatus)
    case apps([GlassesApp])
    case result(command: String, ok: Bool, message: String)

    static func decode(_ data: Data) -> HubEvent? {
        struct Head: Decodable { let type: String }
        struct Apps: Decodable { let apps: [GlassesApp] }
        struct Result: Decodable { let cmd: String; let ok: Bool; let message: String }
        let d = JSONDecoder()
        guard let head = try? d.decode(Head.self, from: data) else { return nil }
        switch head.type {
        case "status": return (try? d.decode(HubStatus.self, from: data)).map(HubEvent.status)
        case "apps": return (try? d.decode(Apps.self, from: data)).map { .apps($0.apps) }
        case "result": return (try? d.decode(Result.self, from: data)).map { .result(command: $0.cmd, ok: $0.ok, message: $0.message) }
        default: return nil
        }
    }
}
