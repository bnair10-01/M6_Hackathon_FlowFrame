import MapKit
import SwiftUI

/// Worn Run app's root: the form inside a navigation stack.
@available(iOS 17.0, *)
struct SetupView: View {
    @ObservedObject var session: RunSession
    @ObservedObject var link: RunLink

    var body: some View {
        NavigationStack {
            WornRunForm(session: session, link: link)
                .navigationTitle("Worn Run")
        }
    }
}

/// The only screen you touch: pick what to run, press Start, pocket the phone.
/// Also hosted inside MLDemo (which supplies its own navigation bar).
@available(iOS 17.0, *)
struct WornRunForm: View {
    @ObservedObject var session: RunSession
    @ObservedObject var link: RunLink
    @State private var query = ""
    @State private var results: [MKMapItem] = []
    @State private var searching = false

    var body: some View {
        Form {
            glassesSection
            Section("Debug") {
                NavigationLink("GPS diagnostics") {
                    GPSDiagnosticsView(session: session, link: link)
                }
            }
            if !session.running {
                Section("Run") {
                    Picker("Mode", selection: $session.mode) {
                        ForEach(RunSession.Mode.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    switch session.mode {
                    case .free: Text("Pace, distance and time, no route.").foregroundStyle(.secondary)
                    case .navigate: destinationSearch
                    }
                    if let error = session.routeError { Text(error).foregroundStyle(.red) }
                }
                if session.mode != .free, let route = session.route { routeSection(route) }
                settingsSection
                togglesSection
                demoSection
            } else {
                liveSection
                togglesSection
                if session.simulate { demoControls }
            }
            Section {
                startStopButton
            }
        }
        .onAppear { session.prepare() }
    }

    private var glassesSection: some View {
        Section("Glasses") {
            HStack {
                Image(systemName: link.isReady ? "eyeglasses" : "antenna.radiowaves.left.and.right")
                    .foregroundStyle(link.isReady ? .green : .secondary)
                Text(link.state.description)
                Spacer()
                if link.isReady {
                    Button("Forget", role: .destructive) { link.forget() }.font(.footnote)
                }
            }
            if !link.isReady {
                Text("Open Worn Run on the glasses. They connect automatically and reconnect if they drop.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if session.locationDenied && !session.simulate {
                Text("Location is off for Worn Run. Turn it on in Settings so the glasses get pace and distance.")
                    .font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private var destinationSearch: some View {
        Group {
            HStack {
                TextField("Search a place", text: $query).onSubmit(search)
                if searching { ProgressView() }
            }
            ForEach(results, id: \.self) { item in
                Button {
                    results = []
                    Task { await session.navigate(to: item) }
                } label: {
                    VStack(alignment: .leading) {
                        Text(item.name ?? "Place")
                        if let subtitle = item.placemark.title { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
        }
    }

    private func search() {
        guard !query.isEmpty else { return }
        searching = true
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = query
        if let fix = session.lastFix {
            request.region = MKCoordinateRegion(center: fix.coordinate, latitudinalMeters: 20_000, longitudinalMeters: 20_000)
        }
        MKLocalSearch(request: request).start { response, _ in
            searching = false
            results = Array((response?.mapItems ?? []).prefix(6))
        }
    }

    private func routeSection(_ route: PlannedRoute) -> some View {
        Section(route.name) {
            Map {
                MapPolyline(coordinates: route.coordinates).stroke(.cyan, lineWidth: 4)
                if let start = route.coordinates.first { Marker("Start", coordinate: start).tint(.green) }
                if let end = route.coordinates.last { Marker("Finish", coordinate: end).tint(.red) }
                UserAnnotation()
            }
            .frame(height: 220)
            .listRowInsets(EdgeInsets())
            Text("\(formatDistance(route.lengthMeters)) · \(route.turns.count) turns")
                .foregroundStyle(.secondary)
        }
    }

    private var settingsSection: some View {
        Section("Settings") {
            Picker("Units", selection: $session.metric) {
                Text("Miles · feet · °F").tag(false)
                Text("Kilometres · metres · °C").tag(true)
            }
        }
    }

    /// Feature toggles. Shown before and during a run; changes reach the glasses at once.
    private var togglesSection: some View {
        Section {
            Toggle("Display always on", isOn: $session.displayAlwaysOn)
            if !session.displayAlwaysOn {
                Stepper("Sleep after \(session.displaySleepSec) s", value: $session.displaySleepSec, in: 1...30)
            }
            Toggle("Auto-pause", isOn: $session.autoPause)
            Toggle("Record glasses video", isOn: $session.videoRecording)
                .disabled(!link.isReady)
            Toggle("Show GPS coordinates", isOn: $session.showGps)
        } header: {
            Text("Glasses")
        } footer: {
            Text("You can change these during a run. Swipe back on the glasses to toggle video recording. Clips are saved in DCIM/WornRun on the glasses. With the display asleep, tap the touchpad to wake it; turns and alerts still appear.")
        }
    }

    private var demoPacePicker: some View {
        Picker("Pace", selection: $session.demoPaceSecPerMile) {
            ForEach(RunSession.demoPaces(metric: session.metric), id: \.self) { secPerMile in
                let sec = Int((session.metric ? secPerMile / 1.609344 : secPerMile).rounded())
                Text("\(sec / 60):\(String(format: "%02d", sec % 60)) /\(session.metric ? "km" : "mi")").tag(secPerMile)
            }
        }
    }

    private var demoSection: some View {
        Section {
            Toggle("Simulate the run", isOn: $session.simulate)
            if session.simulate { demoPacePicker }
        } header: {
            Text("Demo")
        } footer: {
            Text("For showing Worn Run indoors: the phone sends a runner moving along the route (or north, for a free run) over the real Bluetooth link.")
        }
    }

    private var demoControls: some View {
        Section("Demo controls") {
            Button("Skip ahead 200 m") { session.demoSkipAhead() }
                .disabled(session.mode == .free)
            Toggle("Go off route", isOn: $session.demoOffRoute)
                .disabled(session.mode == .free)
            demoPacePicker
        }
    }

    private var liveSection: some View {
        Section("Live") {
            if let progress = link.routeProgress {
                ProgressView("Sending route…", value: progress)
            }
            if let s = link.status {
                LabeledContent("Glasses", value: s.autoPaused ? "Auto-paused" : s.state.capitalized)
                LabeledContent("Time", value: Duration.milliseconds(s.elapsedMs).formatted(.time(pattern: .hourMinuteSecond)))
                LabeledContent("Distance", value: formatDistance(s.distanceM))
            }
            if let fix = session.lastFix {
                LabeledContent(session.simulate ? "Simulated GPS" : "GPS accuracy", value: "±\(formatShort(fix.horizontalAccuracy))")
                if session.showGps {
                    LabeledContent("Position", value: String(format: "%.5f, %.5f", fix.coordinate.latitude, fix.coordinate.longitude))
                    if fix.speed >= 0 { LabeledContent("GPS speed", value: formatSpeed(fix.speed)) }
                }
            }
            if let bpm = session.heartRateBpm {
                LabeledContent(session.simulate ? "Simulated heart rate" : "Heart rate", value: "\(Int(bpm)) bpm")
            }
            if let t = session.airTempC {
                LabeledContent("Air", value: session.metric ? "\(Int(t.rounded()))°C" : "\(Int((t * 9 / 5 + 32).rounded()))°F")
            }
            Text("You can lock the phone and put it away. Control the run from the glasses' touchpad.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var startStopButton: some View {
        Button {
            session.running ? session.stop() : session.start()
        } label: {
            Text(session.running ? "Finish run" : (session.simulate ? "Start demo run" : "Start run"))
                .font(.title3.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        }
        .buttonStyle(.borderedProminent)
        .tint(session.running ? .red : .green)
        .disabled(!session.running && (!link.isReady || (session.mode != .free && session.route == nil)))
        .listRowInsets(EdgeInsets())
    }

    /// Small distances: feet or metres, matching the units setting.
    private func formatShort(_ m: Double) -> String {
        session.metric ? "\(Int(m.rounded())) m" : "\(Int((m * 3.28084).rounded())) ft"
    }

    private func formatSpeed(_ mps: Double) -> String {
        session.metric ? String(format: "%.1f km/h", mps * 3.6) : String(format: "%.1f mph", mps * 2.23694)
    }

    private func formatDistance(_ m: Double) -> String {
        let miles = Measurement(value: m, unit: UnitLength.meters).converted(to: .miles).value
        return miles.formatted(.number.precision(.fractionLength(2))) + " mi"
    }
}

@available(iOS 17.0, *)
private struct GPSDiagnosticsView: View {
    @ObservedObject var session: RunSession
    @ObservedObject var link: RunLink

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            Form {
                Section("Phone GPS") {
                    LabeledContent("Authorization", value: session.locationAuthorization)
                    LabeledContent("Location updates", value: session.running ? "Streaming run" : "Map updates only")
                    if let fix = session.lastFix {
                        LabeledContent("Coordinates", value: coordinates(fix.coordinate.latitude, fix.coordinate.longitude))
                        LabeledContent("Accuracy", value: "±\(shortDistance(fix.horizontalAccuracy))")
                        LabeledContent("Fix timestamp", value: fix.timestamp.formatted(date: .omitted, time: .standard))
                        LabeledContent("Fix age", value: age(fix.timestamp, now: context.date))
                    } else {
                        Text("No location fix received yet").foregroundStyle(.secondary)
                    }
                }

                Section("Phone → glasses") {
                    LabeledContent("Bluetooth", value: link.state.description)
                    if let fix = session.lastFixSent, let sentAt = session.lastFixSentAt {
                        LabeledContent("Last fix sent", value: sentAt.formatted(date: .omitted, time: .standard))
                        LabeledContent("Sent coordinates", value: coordinates(fix.coordinate.latitude, fix.coordinate.longitude))
                        LabeledContent("Sent age", value: age(sentAt, now: context.date))
                    } else {
                        Text("No GPS fix sent during this run").foregroundStyle(.secondary)
                    }
                }

                Section("Glasses receipt") {
                    if let status = link.status {
                        if let lat = status.gpsLatitude, let lon = status.gpsLongitude {
                            LabeledContent("Received coordinates", value: coordinates(lat, lon))
                            if let fixAge = status.gpsFixAgeMs {
                                LabeledContent("Received fix age", value: "\(max(0, fixAge) / 1000) s")
                            }
                        } else {
                            Text("Glasses have not received a GPS fix").foregroundStyle(.secondary)
                        }
                        if let receivedAt = link.statusReceivedAt {
                            LabeledContent("Status updated", value: age(receivedAt, now: context.date) + " ago")
                        }
                    } else {
                        Text("No status received from glasses yet").foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("GPS diagnostics")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func coordinates(_ lat: Double, _ lon: Double) -> String {
        String(format: "%.6f, %.6f", lat, lon)
    }

    private func age(_ date: Date, now: Date) -> String {
        "\(max(0, Int(now.timeIntervalSince(date).rounded()))) s"
    }

    private func shortDistance(_ meters: Double) -> String {
        session.metric ? "\(Int(meters.rounded())) m" : "\(Int((meters * 3.28084).rounded())) ft"
    }
}
