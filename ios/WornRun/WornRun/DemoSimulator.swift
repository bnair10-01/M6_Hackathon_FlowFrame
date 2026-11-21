import CoreLocation
import Foundation

/// Fakes a runner for live demos: produces one realistic location a second
/// along the route (or due north for a free run), at a chosen pace, with a
/// little GPS jitter. The fixes go over the real BLE link, so the glasses
/// can't tell it from a real run. "Skip ahead" and "off route" make turns
/// and the off-route warning happen on cue. It also fakes a heart rate that
/// climbs from resting toward a level set by the pace, then drifts up slowly.
@available(iOS 17.0, *)
@MainActor
final class DemoSimulator {
    var paceSecPerMile: Double
    /// While true, the runner is 60 m to the right of the route.
    var offRoute = false
    private(set) var alongM = 0.0
    private(set) var heartRate = 85.0
    private var seconds = 0.0

    private let path: [CLLocationCoordinate2D]
    private let cumulative: [Double]
    private var timer: Timer?
    private var send: ((CLLocation) -> Void)?
    private var sendHeartRate: ((Double) -> Void)?

    init(route: PlannedRoute?, start: CLLocationCoordinate2D, paceSecPerMile: Double) {
        self.paceSecPerMile = paceSecPerMile
        if let route, route.coordinates.count >= 2 {
            path = route.coordinates
        } else {
            // Free run: straight north for 10 km.
            path = [start, CLLocationCoordinate2D(latitude: start.latitude + 10_000 / 111_320, longitude: start.longitude)]
        }
        var cum = [0.0]
        for i in 1..<path.count { cum.append(cum[i - 1] + Geo.distance(path[i - 1], path[i])) }
        cumulative = cum
    }

    var lengthM: Double { cumulative.last ?? 0 }

    func start(send: @escaping (CLLocation) -> Void, heartRate: @escaping (Double) -> Void) {
        self.send = send
        sendHeartRate = heartRate
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.step() }
        }
        step()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Jumps the runner forward (to reach the next turn quickly on stage).
    func skip(meters: Double) {
        alongM = min(lengthM - 1, alongM + meters)
    }

    private func step() {
        let speed = RunEngineConstants.metersPerMile / paceSecPerMile
        alongM = min(lengthM - 1, alongM + speed)
        let (point, bearing) = position(at: alongM)
        var lat = point.latitude, lon = point.longitude
        let mLon = 111_320 * cos(lat * .pi / 180)
        if offRoute {
            // 60 m to the right of the direction of travel.
            let right = (bearing + 90) * .pi / 180
            lat += 60 * cos(right) / 111_320
            lon += 60 * sin(right) / mLon
        }
        lat += Double.random(in: -1.5...1.5) / 111_320
        lon += Double.random(in: -1.5...1.5) / mLon
        let fix = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
            altitude: 30 + 8 * sin(alongM / 300), horizontalAccuracy: 5, verticalAccuracy: 4,
            course: bearing, speed: speed + Double.random(in: -0.15...0.15), timestamp: Date())
        send?(fix)
        stepHeartRate(speed: speed)
    }

    /// Steady-state heart rate rises with speed (about 155 bpm at 8:30 /mi)
    /// plus cardiac drift of 1 bpm every 5 minutes; it approaches that level
    /// over about 30 s, with a little beat-to-beat noise.
    private func stepHeartRate(speed: Double) {
        seconds += 1
        let target = min(195, max(60, 60 + 30 * speed + seconds / 300))
        heartRate += (target - heartRate) * (1 - exp(-1.0 / 30))
        sendHeartRate?((heartRate + Double.random(in: -1.5...1.5)).rounded())
    }

    private func position(at along: Double) -> (CLLocationCoordinate2D, Double) {
        var i = 0
        while i < path.count - 2 && cumulative[i + 1] < along { i += 1 }
        let seg = cumulative[i + 1] - cumulative[i]
        let t = seg > 0 ? min(1, max(0, (along - cumulative[i]) / seg)) : 0
        let a = path[i], b = path[i + 1]
        let p = CLLocationCoordinate2D(latitude: a.latitude + (b.latitude - a.latitude) * t,
                                       longitude: a.longitude + (b.longitude - a.longitude) * t)
        return (p, Geo.bearing(a, b))
    }
}

enum RunEngineConstants {
    static let metersPerMile = 1609.344
}
