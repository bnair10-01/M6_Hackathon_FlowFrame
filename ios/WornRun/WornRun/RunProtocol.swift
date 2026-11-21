import CoreBluetooth
import CoreLocation
import Foundation

/// BLE protocol shared with the glasses
/// (glasses-app/run/.../RunProtocol.kt). Keep the two in sync.
/// All integers are little-endian.
enum RunProtocol {
    static let service = CBUUID(string: "7F1D0000-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    /// Phone → glasses, write without response: one 20-byte fix.
    static let fix = CBUUID(string: "7F1D0001-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    /// Phone → glasses, write with response: the route, in chunks.
    static let route = CBUUID(string: "7F1D0002-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    /// Phone → glasses, write with response: UTF-8 JSON command.
    static let control = CBUUID(string: "7F1D0003-5E2A-4C8E-9B1A-3C6F8E2D4A10")
    /// Glasses → phone, notify: UTF-8 JSON status.
    static let status = CBUUID(string: "7F1D0004-5E2A-4C8E-9B1A-3C6F8E2D4A10")

    static let fixBytes = 20
    private static let routeMagic: UInt16 = 0x5257 // "WR"
    private static let routeVersion: UInt8 = 1

    /// lat i32 (1e-7°), lon i32 (1e-7°), alt i16 (dm), speed u16 (cm/s,
    /// 0xFFFF unknown), course u16 (0.01°, 0xFFFF unknown), hAcc u16 (dm),
    /// time u32 (unix s).
    static func encodeFix(_ l: CLLocation) -> Data {
        var d = Data(capacity: fixBytes)
        d.appendLE(Int32((l.coordinate.latitude * 1e7).rounded()))
        d.appendLE(Int32((l.coordinate.longitude * 1e7).rounded()))
        d.appendLE(Int16(clamping: Int((l.altitude * 10).rounded())))
        d.appendLE(l.speed < 0 ? UInt16.max : UInt16(clamping: Int((l.speed * 100).rounded())))
        d.appendLE(l.course < 0 ? UInt16.max : UInt16(clamping: Int((l.course * 100).rounded()) % 36000))
        d.appendLE(UInt16(clamping: Int((max(0, l.horizontalAccuracy) * 10).rounded())))
        d.appendLE(UInt32(clamping: Int(l.timestamp.timeIntervalSince1970)))
        return d
    }

    /// magic u16, version u8, pointCount u16, turnCount u16, points
    /// (lat i32, lon i32)…, turns (pointIndex u16, angle i16, labelLen u8, label)…
    static func encodeRoute(_ route: PlannedRoute) -> Data {
        var d = Data()
        d.appendLE(routeMagic)
        d.append(routeVersion)
        d.appendLE(UInt16(route.coordinates.count))
        d.appendLE(UInt16(route.turns.count))
        for c in route.coordinates {
            d.appendLE(Int32((c.latitude * 1e7).rounded()))
            d.appendLE(Int32((c.longitude * 1e7).rounded()))
        }
        for t in route.turns {
            let label = Data(t.label.utf8.prefix(255))
            d.appendLE(UInt16(t.pointIndex))
            d.appendLE(Int16(clamping: t.angleDeg))
            d.append(UInt8(label.count))
            d.append(label)
        }
        return d
    }

    /// Splits a route body into chunks: seq u16, total u16, payload.
    static func chunks(_ body: Data, maxChunk: Int) -> [Data] {
        let payload = max(1, maxChunk - 4)
        let count = (body.count + payload - 1) / payload
        return (0..<count).map { seq in
            let from = body.startIndex + seq * payload
            let to = min(body.endIndex, from + payload)
            var c = Data()
            c.appendLE(UInt16(seq))
            c.appendLE(UInt16(count))
            c.append(body[from..<to])
            return c
        }
    }

    static func control(_ fields: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: fields)) ?? Data()
    }
}

extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
}
