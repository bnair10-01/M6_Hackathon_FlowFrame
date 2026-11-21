import XCTest
@testable import WornHub

final class HubTests: XCTestCase {
    func testChunksRoundTrip() {
        let message = Data((0..<1000).map { UInt8($0 % 251) })
        let chunks = HubProtocol.split(message, maxChunk: 20)
        XCTAssertTrue(chunks.allSatisfy { $0.count <= 20 })
        var a = ChunkAssembler()
        var out: Data?
        for (i, c) in chunks.enumerated() {
            let r = a.accept(c)
            if i < chunks.count - 1 { XCTAssertNil(r) } else { out = r }
        }
        XCTAssertEqual(out, message)
    }

    /// Same bytes as HubProtocol.kt: seq 0, total 1, little-endian.
    func testChunkHeaderMatchesGlasses() {
        let c = HubProtocol.split(Data("{}".utf8), maxChunk: 180)
        XCTAssertEqual([UInt8](c[0]), [0, 0, 1, 0, 0x7B, 0x7D])
    }

    func testDecodesStatusFromGlasses() throws {
        // Captured from the glasses' /status endpoint.
        let json = """
        {"type":"status","model":"XNM-Glass","android":"9","battery":{"level":92,"charging":true,"full":false,
        "source":"USB","health":"Good","tempC":28,"voltageMv":4455,"currentMa":0,"technology":"","fullMah":241},
        "storage":{"freeBytes":55336038400,"totalBytes":57969438720},
        "wifi":{"enabled":false,"connected":false,"ssid":"","ip":"","port":47900},
        "foreground":"Head Launcher","phones":0,"liveViewers":0}
        """
        guard case .status(let s)? = HubEvent.decode(Data(json.utf8)) else { return XCTFail("not a status") }
        XCTAssertEqual(s.battery.level, 92)
        XCTAssertEqual(s.battery.fullMah, 241)
        XCTAssertNil(s.battery.capacityPercent)
        XCTAssertEqual(s.foreground, "Head Launcher")
        XCTAssertNil(HubHTTP(status: s, token: "t"), "no Wi-Fi, so no HTTP")
    }

    func testDecodesAppsAndResult() {
        guard case .apps(let apps)? = HubEvent.decode(Data(#"{"type":"apps","apps":[{"package":"a.b","label":"Run"}]}"#.utf8)) else {
            return XCTFail()
        }
        XCTAssertEqual(apps.first?.label, "Run")
        guard case .result(let cmd, let ok, let msg)? = HubEvent.decode(Data(#"{"type":"result","cmd":"launch","ok":true,"message":"Opened Run"}"#.utf8)) else {
            return XCTFail()
        }
        XCTAssertEqual(cmd, "launch")
        XCTAssertTrue(ok)
        XCTAssertEqual(msg, "Opened Run")
    }

    func testMJPEGParserSplitsAcrossPackets() {
        let jpeg1 = Data([0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9])
        let jpeg2 = Data([0xFF, 0xD8, 9, 9, 0xFF, 0xD9])
        var stream = Data()
        for j in [jpeg1, jpeg2] {
            stream.append(Data("--wornhubframe\r\nContent-Type: image/jpeg\r\nContent-Length: \(j.count)\r\n\r\n".utf8))
            stream.append(j)
            stream.append(Data("\r\n".utf8))
        }
        var p = MJPEGParser()
        var frames: [Data] = []
        // Feed in awkward 5-byte pieces.
        var i = 0
        while i < stream.count {
            frames += p.feed(stream.subdata(in: i..<min(stream.count, i + 5)))
            i += 5
        }
        XCTAssertEqual(frames, [jpeg1, jpeg2])
    }

    func testVideoURLCarriesToken() {
        let json = #"{"type":"status","model":"m","android":"9","battery":{"level":50,"charging":false,"full":false,"source":"Battery","health":"Good","tempC":30,"voltageMv":4000,"currentMa":0},"storage":{"freeBytes":1,"totalBytes":2},"wifi":{"enabled":true,"connected":true,"ssid":"Home","ip":"192.168.1.20","port":47900},"foreground":"","phones":1,"liveViewers":0}"#
        guard case .status(let s)? = HubEvent.decode(Data(json.utf8)), let http = HubHTTP(status: s, token: "abc") else {
            return XCTFail()
        }
        let item = MediaItem(id: "v1", kind: "video", name: "a.mp4", mime: "video/mp4", takenMs: 0, size: 1, width: 0, height: 0, durationMs: 0)
        XCTAssertEqual(http.videoURL(item).absoluteString, "http://192.168.1.20:47900/media/v1?t=abc")
        XCTAssertEqual(http.request("media").value(forHTTPHeaderField: "Authorization"), "Bearer abc")
    }
}
