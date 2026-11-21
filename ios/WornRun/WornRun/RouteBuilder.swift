import CoreLocation
import Foundation
import MapKit

struct RouteTurn: Equatable {
    let pointIndex: Int
    /// + right, - left, 0…180.
    let angleDeg: Int
    let label: String
}

/// A route ready to send to the glasses.
struct PlannedRoute {
    var name: String
    var coordinates: [CLLocationCoordinate2D]
    var turns: [RouteTurn]

    var lengthMeters: Double {
        zip(coordinates, coordinates.dropFirst()).reduce(0) { $0 + Geo.distance($1.0, $1.1) }
    }
}

enum Geo {
    static func distance(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    /// Initial bearing, degrees 0…360.
    static func bearing(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let p1 = a.latitude * .pi / 180, p2 = b.latitude * .pi / 180
        let dl = (b.longitude - a.longitude) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    static func angleDiff(_ a: Double, _ b: Double) -> Double {
        var d = (a - b).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return d
    }
}

enum RouteBuilder {
    static let maxPoints = 2000

    // MARK: GPX

    enum GPXError: LocalizedError {
        case noPoints
        var errorDescription: String? { "That GPX file has no track or route points." }
    }

    static func fromGPX(_ data: Data, name: String) throws -> PlannedRoute {
        let parser = GPXParser(data: data)
        let points = parser.parse()
        guard points.count >= 2 else { throw GPXError.noPoints }
        let simplified = simplify(points)
        return PlannedRoute(name: name, coordinates: simplified, turns: detectTurns(simplified))
    }

    private final class GPXParser: NSObject, XMLParserDelegate {
        private let parser: XMLParser
        private var track: [CLLocationCoordinate2D] = []
        private var route: [CLLocationCoordinate2D] = []

        init(data: Data) {
            parser = XMLParser(data: data)
            super.init()
            parser.delegate = self
        }

        func parse() -> [CLLocationCoordinate2D] {
            parser.parse()
            return track.count >= 2 ? track : route
        }

        func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?,
                    qualifiedName: String?, attributes: [String: String] = [:]) {
            guard name == "trkpt" || name == "rtept",
                  let lat = attributes["lat"].flatMap(Double.init),
                  let lon = attributes["lon"].flatMap(Double.init) else { return }
            let c = CLLocationCoordinate2D(latitude: lat, longitude: lon)
            if name == "trkpt" { track.append(c) } else { route.append(c) }
        }
    }

    /// Douglas–Peucker, loosening the tolerance until the route fits the protocol.
    static func simplify(_ points: [CLLocationCoordinate2D]) -> [CLLocationCoordinate2D] {
        var tolerance = 2.0
        var result = dedupe(points)
        while result.count > maxPoints {
            result = douglasPeucker(points, tolerance: tolerance)
            tolerance *= 1.5
        }
        return result
    }

    private static func dedupe(_ points: [CLLocationCoordinate2D]) -> [CLLocationCoordinate2D] {
        var out: [CLLocationCoordinate2D] = []
        for p in points where out.last.map({ Geo.distance($0, p) > 1 }) ?? true { out.append(p) }
        return out
    }

    private static func douglasPeucker(_ pts: [CLLocationCoordinate2D], tolerance: Double) -> [CLLocationCoordinate2D] {
        guard pts.count > 2 else { return pts }
        var keep = [Bool](repeating: false, count: pts.count)
        keep[0] = true
        keep[pts.count - 1] = true
        var stack = [(0, pts.count - 1)]
        while let (a, b) = stack.popLast() {
            var maxD = 0.0, index = a
            for i in (a + 1)..<b {
                let d = crossTrack(pts[i], pts[a], pts[b])
                if d > maxD { maxD = d; index = i }
            }
            if maxD > tolerance {
                keep[index] = true
                stack.append((a, index))
                stack.append((index, b))
            }
        }
        return pts.indices.filter { keep[$0] }.map { pts[$0] }
    }

    /// Distance from p to segment a–b in metres (flat projection; fine for short segments).
    private static func crossTrack(_ p: CLLocationCoordinate2D, _ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let mLat = 111_320.0, mLon = 111_320.0 * cos(p.latitude * .pi / 180)
        let ax = (a.longitude - p.longitude) * mLon, ay = (a.latitude - p.latitude) * mLat
        let bx = (b.longitude - p.longitude) * mLon, by = (b.latitude - p.latitude) * mLat
        let dx = bx - ax, dy = by - ay
        let len2 = dx * dx + dy * dy
        let t = len2 == 0 ? 0 : min(1, max(0, -(ax * dx + ay * dy) / len2))
        return hypot(ax + dx * t, ay + dy * t)
    }

    // MARK: Turns (same rules as TurnDetector.kt)

    static func detectTurns(_ pts: [CLLocationCoordinate2D]) -> [RouteTurn] {
        let look = 20.0, minAngle = 35.0, merge = 30.0
        guard pts.count >= 3 else { return [] }
        var cum = [0.0]
        for i in 1..<pts.count { cum.append(cum[i - 1] + Geo.distance(pts[i - 1], pts[i])) }
        func index(from i: Int, toward target: Double, step: Int) -> Int? {
            var j = i
            while pts.indices.contains(j + step) {
                j += step
                if step < 0 ? cum[j] <= target : cum[j] >= target { return j }
            }
            return nil
        }
        var turns: [RouteTurn] = []
        for i in 1..<(pts.count - 1) {
            guard let before = index(from: i, toward: cum[i] - look, step: -1),
                  let after = index(from: i, toward: cum[i] + look, step: 1) else { continue }
            let angle = Geo.angleDiff(Geo.bearing(pts[i], pts[after]), Geo.bearing(pts[before], pts[i]))
            guard abs(angle) >= minAngle else { continue }
            let turn = RouteTurn(pointIndex: i, angleDeg: Int(angle.rounded()), label: label(Int(angle)))
            if let last = turns.last, cum[i] - cum[last.pointIndex] < merge {
                if abs(turn.angleDeg) > abs(last.angleDeg) { turns[turns.count - 1] = turn }
            } else {
                turns.append(turn)
            }
        }
        return turns
    }

    static func label(_ angle: Int) -> String {
        let side = angle > 0 ? "right" : "left"
        switch abs(angle) {
        case 165...: return "U-turn"
        case 120...: return "Sharp \(side)"
        case 60...: return side.capitalized
        default: return "Slight \(side)"
        }
    }

    // MARK: MapKit navigation

    /// Walking directions from the current location to [destination], with turn labels from MapKit's steps.
    static func directions(to destination: MKMapItem) async throws -> PlannedRoute {
        let request = MKDirections.Request()
        request.source = .forCurrentLocation()
        request.destination = destination
        request.transportType = .walking
        let response = try await MKDirections(request: request).calculate()
        guard let route = response.routes.first else { throw GPXError.noPoints }

        let all = route.polyline.coordinates
        let points = simplify(all)
        var turns: [RouteTurn] = []
        // Each step after the first begins at a manoeuvre; place it on the nearest route point.
        for step in route.steps.dropFirst() where !step.instructions.isEmpty {
            guard let start = step.polyline.coordinates.first,
                  let i = points.indices.min(by: { Geo.distance(points[$0], start) < Geo.distance(points[$1], start) }),
                  i > 0, i < points.count - 1 else { continue }
            let angle = Geo.angleDiff(Geo.bearing(points[i], points[i + 1]), Geo.bearing(points[i - 1], points[i]))
            turns.append(RouteTurn(pointIndex: i, angleDeg: Int(angle.rounded()), label: shorten(step.instructions)))
        }
        return PlannedRoute(name: destination.name ?? "Destination", coordinates: points, turns: turns)
    }

    /// "Turn left onto Market Street" → "Left onto Market St" (fits the HUD).
    static func shorten(_ s: String) -> String {
        var t = s.replacingOccurrences(of: "Turn ", with: "")
        for (long, short) in [("Street", "St"), ("Avenue", "Ave"), ("Boulevard", "Blvd"), ("Road", "Rd"), ("Drive", "Dr")] {
            t = t.replacingOccurrences(of: long, with: short)
        }
        if let first = t.first { t = first.uppercased() + t.dropFirst() }
        return t.count > 32 ? String(t.prefix(31)) + "…" : t
    }
}

extension MKPolyline {
    var coordinates: [CLLocationCoordinate2D] {
        var coords = [CLLocationCoordinate2D](repeating: kCLLocationCoordinate2DInvalid, count: pointCount)
        getCoordinates(&coords, range: NSRange(location: 0, length: pointCount))
        return coords
    }
}
