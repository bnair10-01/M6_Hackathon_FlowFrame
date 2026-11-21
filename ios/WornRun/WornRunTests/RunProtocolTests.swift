import CoreLocation
import XCTest
@testable import WornRun

final class RunProtocolTests: XCTestCase {
    /// Byte layout must match RunProtocol.kt exactly.
    func testFixLayoutMatchesGlasses() {
        let loc = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 37.7749295, longitude: -122.4194155),
                             altitude: 52.3, horizontalAccuracy: 4.8, verticalAccuracy: 3,
                             course: 271.5, speed: 3.21, timestamp: Date(timeIntervalSince1970: 1_700_000_000))
        let d = RunProtocol.encodeFix(loc)
        XCTAssertEqual(d.count, 20)
        XCTAssertEqual(d.le(Int32.self, at: 0), 377_749_295)
        XCTAssertEqual(d.le(Int32.self, at: 4), -1_224_194_155)
        XCTAssertEqual(d.le(Int16.self, at: 8), 523)
        XCTAssertEqual(d.le(UInt16.self, at: 10), 321)
        XCTAssertEqual(d.le(UInt16.self, at: 12), 27150)
        XCTAssertEqual(d.le(UInt16.self, at: 14), 48)
        XCTAssertEqual(d.le(UInt32.self, at: 16), 1_700_000_000)
    }

    func testUnknownSpeedAndCourse() {
        let loc = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 2), altitude: 0,
                             horizontalAccuracy: 10, verticalAccuracy: -1, course: -1, speed: -1, timestamp: Date())
        let d = RunProtocol.encodeFix(loc)
        XCTAssertEqual(d.le(UInt16.self, at: 10), 0xFFFF)
        XCTAssertEqual(d.le(UInt16.self, at: 12), 0xFFFF)
    }

    func testRouteHeaderAndChunks() {
        let coords = (0..<300).map { CLLocationCoordinate2D(latitude: 37 + Double($0) * 1e-4, longitude: -122) }
        let route = PlannedRoute(name: "t", coordinates: coords, turns: [RouteTurn(pointIndex: 10, angleDeg: -90, label: "Left")])
        let body = RunProtocol.encodeRoute(route)
        XCTAssertEqual(body.le(UInt16.self, at: 0), 0x5257)
        XCTAssertEqual(body[2], 1)
        XCTAssertEqual(body.le(UInt16.self, at: 3), 300)
        XCTAssertEqual(body.le(UInt16.self, at: 5), 1)
        XCTAssertEqual(body.count, 7 + 300 * 8 + 5 + 4)

        let chunks = RunProtocol.chunks(body, maxChunk: 180)
        XCTAssertEqual(chunks.count, Int((Double(body.count) / 176).rounded(.up)))
        XCTAssertEqual(chunks[1].le(UInt16.self, at: 0), 1)
        XCTAssertEqual(chunks[1].le(UInt16.self, at: 2), UInt16(chunks.count))
        XCTAssertEqual(chunks.reduce(0) { $0 + $1.count - 4 }, body.count)
    }

    func testTurnDetectionMatchesGlasses() {
        // East 300 m, north 300 m (left), east 300 m (right): same case as UnitsAndTurnsTest.kt.
        let lat0 = 37.0, mLat = 111_320.0, mLon = 111_320.0 * cos(lat0 * .pi / 180)
        var pts: [CLLocationCoordinate2D] = []
        for i in 0...30 { pts.append(.init(latitude: lat0, longitude: -122 + Double(i) * 10 / mLon)) }
        for i in 1...30 { pts.append(.init(latitude: lat0 + Double(i) * 10 / mLat, longitude: -122 + 300 / mLon)) }
        for i in 1...30 { pts.append(.init(latitude: lat0 + 300 / mLat, longitude: -122 + (300 + Double(i) * 10) / mLon)) }
        let turns = RouteBuilder.detectTurns(pts)
        XCTAssertEqual(turns.count, 2)
        XCTAssertEqual(turns[0].label, "Left")
        XCTAssert((-100 ... -80).contains(turns[0].angleDeg))
        XCTAssertEqual(turns[1].label, "Right")
    }

    func testGPXParsingAndSimplifying() throws {
        var gpx = "<gpx><trk><trkseg>"
        for i in 0..<5000 { gpx += "<trkpt lat=\"\(37 + Double(i) * 1e-5)\" lon=\"-122\"/>" }
        gpx += "</trkseg></trk></gpx>"
        let route = try RouteBuilder.fromGPX(Data(gpx.utf8), name: "line")
        XCTAssertLessThanOrEqual(route.coordinates.count, RouteBuilder.maxPoints)
        XCTAssertEqual(route.lengthMeters, 5556, accuracy: 30)
    }

    func testShortensInstructions() {
        XCTAssertEqual(RouteBuilder.shorten("Turn left onto Market Street"), "Left onto Market St")
    }
}

private extension Data {
    func le<T: FixedWidthInteger>(_ type: T.Type, at offset: Int) -> T {
        var v: T = 0
        _ = Swift.withUnsafeMutableBytes(of: &v) { copyBytes(to: $0, from: (startIndex + offset)..<(startIndex + offset + MemoryLayout<T>.size)) }
        return T(littleEndian: v)
    }
}
