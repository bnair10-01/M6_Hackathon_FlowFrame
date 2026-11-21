import CoreLocation
import Foundation
import MapKit

/// Runs the phone's part of a run: sends settings and the route, then streams
/// location (1 Hz) and the air temperature to the glasses until stopped. Keeps
/// working with the screen off: background location keeps the app alive and
/// CoreBluetooth reconnects on its own.
///
/// With [simulate] on, a [DemoSimulator] supplies the fixes instead of GPS,
/// for demos indoors.
@available(iOS 17.0, *)
@MainActor
final class RunSession: NSObject, ObservableObject {
    enum Mode: String, CaseIterable, Identifiable {
        // GPX import is hidden for now; RouteBuilder.fromGPX still exists.
        case free = "Free run", navigate = "Navigate"
        var id: String { rawValue }
    }

    @Published var mode: Mode = .free
    private var isUpdatingVideoFromGlasses = false
    @Published var videoRecording = false {
        didSet {
            guard !isUpdatingVideoFromGlasses, oldValue != videoRecording else { return }
            link.send(control: ["cmd": "video", "recording": videoRecording])
        }
    }
    @Published var route: PlannedRoute?
    @Published var routeError: String?
    @Published private(set) var running = false
    @Published private(set) var lastFix: CLLocation?
    @Published private(set) var lastFixSentAt: Date?
    @Published private(set) var lastFixSent: CLLocation?
    @Published private(set) var airTempC: Double?
    /// Latest heart rate sent to the glasses (only the demo supplies one for now).
    @Published private(set) var heartRateBpm: Double?
    @Published private(set) var locationDenied = false

    var locationAuthorization: String {
        switch location.authorizationStatus {
        case .notDetermined: "Not requested"
        case .restricted: "Restricted"
        case .denied: "Denied"
        case .authorizedAlways: "Always"
        case .authorizedWhenInUse: "While using"
        @unknown default: "Unknown"
        }
    }

    // Demo mode.
    @Published var simulate = false
    @Published var demoPaceSecPerMile: Double = 510 { // 8:30 /mi
        didSet { simulator?.paceSecPerMile = demoPaceSecPerMile }
    }
    @Published var demoOffRoute = false {
        didSet { simulator?.offRoute = demoOffRoute }
    }
    private var simulator: DemoSimulator?

    let link: RunLink
    private let location = CLLocationManager()
    private var lastSentAt = Date.distantPast
    private var weatherTask: Task<Void, Never>?

    // Settings, remembered between runs. Each change goes to the glasses at
    // once, so the toggles also work mid-run.

    /// One switch for every unit in both apps: mi/ft/mph/°F or km/m/km·h/°C.
    @Published var metric: Bool = UserDefaults.standard.object(forKey: "metric") as? Bool
        ?? (Locale.current.measurementSystem != .us) {
        didSet {
            UserDefaults.standard.set(metric, forKey: "metric")
            demoPaceSecPerMile = Self.nearest(demoPaceSecPerMile, in: Self.demoPaces(metric: metric))
            sendSettings()
        }
    }
    /// Pause the clock and distance when you stop moving.
    @Published var autoPause: Bool = UserDefaults.standard.object(forKey: "autoPause") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(autoPause, forKey: "autoPause")
            sendSettings()
        }
    }
    /// Temporary: show coordinates on the glasses to check GPS mid-run.
    @Published var showGps: Bool = UserDefaults.standard.object(forKey: "showGps") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(showGps, forKey: "showGps")
            sendSettings()
        }
    }

    /// Keep the glasses' HUD lit instead of sleeping after a touch.
    @Published var displayAlwaysOn: Bool = UserDefaults.standard.bool(forKey: "displayAlwaysOn") {
        didSet {
            UserDefaults.standard.set(displayAlwaysOn, forKey: "displayAlwaysOn")
            sendSettings()
        }
    }
    /// Seconds the HUD stays lit after a touch on the glasses.
    @Published var displaySleepSec: Int = UserDefaults.standard.object(forKey: "displaySleepSec") as? Int ?? 3 {
        didSet {
            UserDefaults.standard.set(displaySleepSec, forKey: "displaySleepSec")
            sendSettings()
        }
    }

    init(link: RunLink) {
        self.link = link
        super.init()
        demoPaceSecPerMile = Self.nearest(demoPaceSecPerMile, in: Self.demoPaces(metric: metric))
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBest
        location.activityType = .fitness
        location.distanceFilter = kCLDistanceFilterNone
        location.pausesLocationUpdatesAutomatically = false
        link.onStatus = { [weak self] old, new in self?.glassesStatusChanged(from: old, to: new) }
    }

    /// The glasses can start or finish a run from their touchpad, and the
    /// phone must follow: the glasses have no GPS, so without this a run
    /// started there never gets location (no speed, "Waiting for GPS…").
    private func glassesStatusChanged(from old: RunLink.GlassesStatus?, to new: RunLink.GlassesStatus) {
        if let recording = new.videoRecording, recording != videoRecording {
            isUpdatingVideoFromGlasses = true
            videoRecording = recording
            isUpdatingVideoFromGlasses = false
        }
        if old == nil { sendSettings() } // just (re)connected
        guard old?.state != new.state else { return }
        if ["RUNNING", "PAUSED"].contains(new.state), !running {
            startStreaming()
        } else if new.state == "FINISHED", running {
            stopStreaming()
        }
    }

    private func sendSettings() {
        guard link.isReady else { return }
        link.send(control: ["cmd": "settings", "units": metric ? "km" : "mi",
                            "displaySleepSec": displayAlwaysOn ? 0 : displaySleepSec,
                            "autoPause": autoPause, "showGps": showGps])
    }

    /// Ask for location early so the map can center and directions can start from here.
    func prepare() {
        if location.authorizationStatus == .notDetermined {
            location.requestWhenInUseAuthorization()
        }
        location.startUpdatingLocation()
    }

    func navigate(to item: MKMapItem) async {
        do {
            route = try await RouteBuilder.directions(to: item)
            routeError = nil
        } catch {
            routeError = "No walking route found: \(error.localizedDescription)"
        }
    }

    /// Demo paces offered in the current unit, stored as seconds per mile.
    static func demoPaces(metric: Bool) -> [Double] {
        metric ? [270.0, 285, 300, 315, 330, 360, 390, 420].map { $0 * 1.609344 }
            : [420.0, 450, 480, 510, 540, 600, 660]
    }

    private static func nearest(_ value: Double, in options: [Double]) -> Double {
        options.min { abs($0 - value) < abs($1 - value) } ?? value
    }

    /// Demo: jump the simulated runner forward.
    func demoSkipAhead(meters: Double = 200) {
        simulator?.skip(meters: meters)
    }

    func start() {
        guard link.isReady else { return }
        sendSettings()
        if mode != .free, let route {
            link.send(route: route)
        } else {
            link.send(control: ["cmd": "clearRoute"])
        }
        if let airTempC { link.send(control: ["cmd": "weather", "airTempC": airTempC]) }
        link.send(control: ["cmd": "start"])
        startStreaming()
    }

    /// Streams location (or the demo runner) and weather to the glasses.
    private func startStreaming() {
        // A run started from the glasses may come before the form ever
        // appeared (e.g. in Worn Hub), so location may not be asked for yet.
        if location.authorizationStatus == .notDetermined {
            location.requestWhenInUseAuthorization()
        }
        location.allowsBackgroundLocationUpdates = true
        location.showsBackgroundLocationIndicator = true
        location.startUpdatingLocation()
        running = true
        if simulate {
            // Start where the route starts, else where the phone is (or a default).
            let start = route?.coordinates.first ?? lastFix?.coordinate
                ?? CLLocationCoordinate2D(latitude: 37.7694, longitude: -122.4862)
            let sim = DemoSimulator(route: mode == .free ? nil : route, start: start, paceSecPerMile: demoPaceSecPerMile)
            sim.offRoute = demoOffRoute
            simulator = sim
            sim.start(send: { [weak self] fix in
                guard let self else { return }
                lastFix = fix
                if link.send(fix: RunProtocol.encodeFix(fix)) {
                    lastFixSent = fix
                    lastFixSentAt = Date()
                }
            }, heartRate: { [weak self] bpm in
                guard let self else { return }
                heartRateBpm = bpm
                // Live data: skip it while disconnected rather than queue a backlog.
                if link.isReady { link.send(control: ["cmd": "hr", "bpm": bpm]) }
            })
        }
        startWeather()
    }

    func stop() {
        link.send(control: ["cmd": "stop"])
        stopStreaming()
    }

    private func stopStreaming() {
        running = false
        simulator?.stop()
        simulator = nil
        heartRateBpm = nil
        demoOffRoute = false
        weatherTask?.cancel()
        location.allowsBackgroundLocationUpdates = false
    }

    // MARK: Air temperature (Open-Meteo, no key; position rounded to ~10 km)

    private func startWeather() {
        weatherTask?.cancel()
        weatherTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshWeather()
                try? await Task.sleep(for: .seconds(600))
            }
        }
    }

    private func refreshWeather() async {
        guard let fix = lastFix else { return }
        let lat = (fix.coordinate.latitude * 10).rounded() / 10
        let lon = (fix.coordinate.longitude * 10).rounded() / 10
        guard let url = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(lat)&longitude=\(lon)&current=temperature_2m") else { return }
        struct Reply: Decodable {
            struct Current: Decodable { let temperature_2m: Double }
            let current: Current
        }
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let reply = try? JSONDecoder().decode(Reply.self, from: data) else { return }
        airTempC = reply.current.temperature_2m
        if running { link.send(control: ["cmd": "weather", "airTempC": reply.current.temperature_2m]) }
    }
}

@available(iOS 17.0, *)
extension RunSession: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated {
            guard let fix = locations.last else { return }
            if simulator != nil { return } // the simulator supplies fixes
            let first = lastFix == nil
            lastFix = fix
            if first, running { Task { await refreshWeather() } }
            // One fix a second is plenty; the glasses smooth over 10 s.
            guard running, fix.timestamp.timeIntervalSince(lastSentAt) >= 0.9, fix.horizontalAccuracy >= 0 else { return }
            guard link.send(fix: RunProtocol.encodeFix(fix)) else { return }
            lastSentAt = fix.timestamp
            lastFixSent = fix
            lastFixSentAt = Date()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated {
            locationDenied = [.denied, .restricted].contains(manager.authorizationStatus)
        }
    }
}
